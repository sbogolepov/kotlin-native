package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.irasdescriptors.constructedClass
import org.jetbrains.kotlin.backend.konan.irasdescriptors.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.irasdescriptors.name
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private fun getLocalName(parent: FqName, descriptor: IrDeclaration): Name {
    if (descriptor.isAnonymousObject) {
        return Name.identifier("anon")
    }

    return descriptor.name
}

private fun getFqName(descriptor: IrDeclaration): FqName {
    val parent = descriptor.parent
    val parentFqName = when (parent) {
        is IrPackageFragment -> parent.fqName
        is IrDeclaration -> getFqName(parent)
        else -> error(parent)
    }

    val localName = getLocalName(parentFqName, descriptor)
    return parentFqName.child(localName)
}

class SSATypeMapper {
    fun map(irType: IrType): SSAType {
        return if (irType.isPrimitiveType()) when {
            irType.isBoolean() -> SSAPrimitiveType.BOOL
            irType.isByte() -> SSAPrimitiveType.BYTE
            irType.isShort() -> SSAPrimitiveType.SHORT
            irType.isInt() -> SSAPrimitiveType.INT
            irType.isLong() -> SSAPrimitiveType.LONG
            else -> TODO("Unsupported primitive type: ${irType}")
        } else {
            SSAWrapperType(irType)
        }
    }
}

private const val DEBUG = true

private fun ssaDebug(message: String) { if (DEBUG) println(message) }

class SSADeclarationsMapper(val module: SSAModule, val typeMapper: SSATypeMapper) {

    fun mapFunction(func: IrFunction): SSAFunction {
        module.index.functions.find { it.first == func }?.let {
            return it.second
        }
        val type = SSAFuncType(
                typeMapper.map(func.returnType),
                func.valueParameters.map { typeMapper.map(it.type) }
        )
        val ssaFunction = SSAFunction(getFqName(func).asString(), type, func)
        module.imports += ssaFunction
        return ssaFunction
    }
}

class SSAModuleIndex {
    val functions = mutableListOf<Pair<IrFunction, SSAFunction>>()
}

// Better to transform to some sort of SymbolTable
class SSAModuleBuilder {

    private val typeMapper = SSATypeMapper()

    private fun createSSAFuncFromIr(irFunction: IrFunction): SSAFunction {
        val name = irFunction.name.asString()
        val type = SSAFuncType(
                typeMapper.map(irFunction.returnType),
                irFunction.valueParameters.map { typeMapper.map(it.type) }
        )
        return SSAFunction(name, type)
    }

    private fun createIndex(irModule: IrModuleFragment): SSAModuleIndex {
        val index = SSAModuleIndex()
        for (irFile in irModule.files) {
            for (decl in irFile.declarations) {
                if (decl is IrFunction) {
                    val ssa = createSSAFuncFromIr(decl)
                    index.functions += decl to ssa
                }
                if (decl is IrClass) {
                    index.functions += indexClassMethods(decl)
                }
            }
        }
        return index
    }

    fun build(irModule: IrModuleFragment): SSAModule {
        val index = createIndex(irModule)
        val module = SSAModule(irModule.name.asString(), index)
        for ((ir, ssa) in index.functions) {
            module.functions += SSAFunctionBuilder(ssa, module).build(ir)
        }
        return module
    }

    private fun indexClassMethods(irClass: IrClass): List<Pair<IrFunction, SSAFunction>> {
        val methods = mutableListOf<Pair<IrFunction, SSAFunction>>()
        for (decl in irClass.declarations) {
            if (decl is IrProperty) {
                decl.getter?.let {
                    val fn = createSSAFuncFromIr(it)
                    fn.metadata += "getter"
                    methods += it to fn
                }
                decl.setter?.let {
                    val fn = createSSAFuncFromIr(it)
                    fn.metadata += "setter"
                    methods += it to fn
                }
            }
        }
        return methods
    }
}

class SSABlockIdGenerator {
    fun next(suffix: String): SSABlockId {
        return SSABlockId.Simple(suffix)
    }
}

// TODO: Pass context about class here?
class SSAFunctionBuilder(val func: SSAFunction, val module: SSAModule) {

    private val typeMapper = SSATypeMapper()

    private val declMapper = SSADeclarationsMapper(module, typeMapper)

    var blockIdGen = SSABlockIdGenerator()


    var curBlock = func.entry

    private fun SSABlock.addParam(type: SSAType): SSABlockParam =
            SSABlockParam(type, this).also { this.params.add(it) }

    // SSA construction-related
    private val construct = object {
        val currentDef = mutableMapOf<IrValueDeclaration, MutableMap<SSABlock, SSAValue>>()
        val incompletePhis = mutableMapOf<SSABlock, MutableMap<IrValueDeclaration, SSAValue>>()

        fun dumpTable() {
            for ((def, map) in currentDef) {
                println("--- ${def.name.asString()} --- $def")
                for ((k, v) in map) {
                    println("$k $v")
                }
            }
        }

        fun writeVariable(block: SSABlock, variable: IrValueDeclaration, value: SSAValue) {
            val blocks = currentDef.getOrPut(variable) { mutableMapOf() }
            blocks[block] = value
        }

        fun readVariable(block: SSABlock, variable: IrValueDeclaration): SSAValue =
                if (block in currentDef.getOrPut(variable) { mutableMapOf() }) {
                    currentDef[variable]!![block]!!
                } else {
                    readRecursiveVariable(block, variable)
                }

        private fun readRecursiveVariable(block: SSABlock, variable: IrValueDeclaration): SSAValue {
            val value: SSAValue = when {
                !block.sealed -> {
                    val param = block.addParam(typeMapper.map(variable.type))
                    incompletePhis.getOrPut(block) { mutableMapOf() }[variable] = param
                    param
                }
                block.preds.size == 1 -> {
                    readVariable(block.preds.first().from, variable)
                }
                // Break potential cycles with operandless phi val
                else -> {
                    val param = block.addParam(typeMapper.map(variable.type))
                    writeVariable(block, variable, param)
                    addParamValues(variable, param)
                }
            }
            writeVariable(block, variable, value)
            return value
        }

        private fun addParamValues(variable: IrValueDeclaration, param: SSABlockParam): SSAValue {
            for (edge in param.owner.preds) {
                edge.args.add(readVariable(edge.from, variable))
            }
            return param //tryRemoveTrivialPhi(phi)
        }

        // TODO: is it valid to always replace phi with previous phi?
//        private fun tryRemoveTrivialPhi(phi: SSAPhi): SSAValue {
//            var same: SSAValue? = null
//
//            for (op in phi.operands) {
//                if (op === same || op === phi) {
//                    continue // unique value or self reference
//                }
//                if (same != null) {
//                    return phi // phi merges at least 2 values -> not trivial
//                }
//                same = op
//            }
//            if (same == null) {
//                same = SSAConstant.Undef // phi is unreachable or in the entry block
//            }
//            phi.users.remove(phi)
//            val users = phi.users
//            phi.replaceBy(same)
//            users.filterIsInstance<SSAPhi>().forEach { tryRemoveTrivialPhi(it) }
//            return same
//        }
    }

    private fun addBlock(name: String = ""): SSABlock {
        return SSABlock(func, blockIdGen.next(name)).add()
    }

    private fun IrType.map() = typeMapper.map(this)

    fun build(irFunction: IrFunction): SSAFunction {

        // TODO: make parameters explicit parameters of the entry block
        irFunction.dispatchReceiverParameter?.let {
            // TODO: Reflect in owner type somehow
            val receiver = SSAReceiver(it.type.map())
            func.receiver = receiver
            construct.writeVariable(func.entry, it, receiver)
        }

        irFunction.valueParameters.forEach {
            val param = SSAFuncArgument(it.name.asString(), it.type.map())
            func.params.add(param)
            construct.writeVariable(func.entry, it, param)
        }
        seal(func.entry)
        irFunction.body?.let { generateBody(it) }
        return func
    }

    private fun seal(block: SSABlock) {
        block.sealed = true
    }

    private fun generateBody(irBody: IrBody) = when (irBody) {
        is IrBlockBody -> {
            for (stmt in irBody.statements) {
                generateStatement(stmt)
            }
        }
        is IrExpressionBody -> TODO("$irBody")
        is IrSyntheticBody -> TODO("$irBody")
        else -> TODO()
    }

    private fun generateStatement(irStmt: IrStatement) {
        when (irStmt) {
            is IrExpression -> evalExpression(irStmt)
            is IrVariable -> generateVariable(irStmt)
            else -> TODO("$irStmt")
        }
    }

    private fun generateVariable(irVariable: IrVariable) {
        val value = irVariable.initializer?.let { evalExpression(it) }
                // TODO: explain
                ?: SSAConstant.Undef
        construct.writeVariable(curBlock, irVariable, value)
    }

    private fun evalExpression(irExpr: IrExpression): SSAValue = when (irExpr) {
        is IrCall -> evalCall(irExpr)
        is IrDelegatingConstructorCall -> evalDelegatingConstructorCall(irExpr)
        is IrGetValue -> evalGetValue(irExpr)
        is IrConst<*> -> evalConstant(irExpr)
        is IrReturn -> evalReturn(irExpr)
        is IrGetObjectValue -> evalGetObjectValue(irExpr)
        is IrWhileLoop -> evalWhileLoop(irExpr)
        is IrWhen -> evalWhen(irExpr)
        is IrSetVariable -> evalSetVariable(irExpr)
        is IrContainerExpression -> evalContainerExpression(irExpr)
        is IrGetField -> evalGetField(irExpr)
        is IrSetField -> evalSetField(irExpr)
        else -> TODO("$irExpr")
    }

    private fun evalSetField(irExpr: IrSetField): SSAValue {
        val receiver = evalExpression(irExpr.receiver!!)
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        val value = evalExpression(irExpr.value)
        return +SSASetField(receiver, field, value, curBlock)
    }

    private fun evalGetField(irExpr: IrGetField): SSAValue {
        // Static variables has no receiver
        val receiver = evalExpression(irExpr.receiver!!)
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        return +SSAGetField(receiver, field, curBlock)
    }

    private fun evalDelegatingConstructorCall(irCall: IrDelegatingConstructorCall): SSAValue {
        return +SSANOP("Delegating constructor", curBlock)
    }

    private fun evalWhen(irWhen: IrWhen): SSAValue =
        WhenGenerator(irWhen).generate()

    private inner class WhenGenerator(val irWhen: IrWhen) {

        private val exitBlock = SSABlock(func, blockIdGen.next("when_exit"))

        private val param: SSABlockParam by lazy {
            exitBlock.addParam(typeMapper.map(irWhen.type))
        }

        private val isExpression = isUnconditional(irWhen.branches.last()) && !irWhen.type.isUnit()

        private fun isUnconditional(branch: IrBranch): Boolean =
                branch.condition is IrConst<*>                            // If branch condition is constant.
                        && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

        // TODO: common exit block
        //  if-expression
        private fun generateWhenCase(branch: IrBranch, isLast: Boolean) {
            val nextBlock = if (isLast) exitBlock else SSABlock(func, blockIdGen.next("when_cond"))
            seal(curBlock)
            val result = if (isUnconditional(branch)) {
                evalExpression(branch.result)
            } else {
                val cond = evalExpression(branch.condition)
                val bodyBlock = addBlock("when_body")
                addCondBr(cond, bodyBlock, nextBlock)
                curBlock = bodyBlock
                seal(curBlock)
                evalExpression(branch.result)
            }

            if (curBlock.body.isEmpty() || !curBlock.body.last().isTerminal()) {
                val br = addBr(exitBlock)
                if (isExpression) {
                    br.edge.args.add(result)
                }
            }
            if (nextBlock != exitBlock) {
                nextBlock.add()
            }
            curBlock = nextBlock
        }

        fun generate(): SSAValue {
            irWhen.branches.forEach {
                generateWhenCase(it, it == irWhen.branches.last())
            }
            exitBlock.add()
            exitBlock.sealed = true
            return if (isExpression) {
                param
            } else {
                getUnit()
            }
        }
    }

    private fun getUnit(): SSAValue = SSAConstant.Undef

    private fun evalSetVariable(setVariable: IrSetVariable): SSAValue {
        val value = evalExpression(setVariable.value)
        construct.writeVariable(curBlock, setVariable.symbol.owner, value)
        return getUnit()
    }

    private fun evalContainerExpression(containerExpr: IrContainerExpression): SSAValue {
        containerExpr.statements.dropLast(1).forEach { generateStatement(it) }
        containerExpr.statements.lastOrNull()?.let {
            if (it is IrExpression) {
                return evalExpression(it)
            } else {
                generateStatement(it)
            }
        }
        return getUnit()
    }

    private fun evalWhileLoop(irLoop: IrWhileLoop): SSAValue {
        val loopHeader = addBlock("loop_header")
        val loopBody = addBlock("loop_body")
        val loopExit = addBlock("loop_exit")

        addBr(loopHeader)

        curBlock = loopHeader
        val condition = evalExpression(irLoop.condition)
        addCondBr(condition, loopBody, loopExit)

        curBlock = loopBody
        seal(loopBody)
        irLoop.body?.let { generateStatement(it) }
        addBr(loopHeader)
        seal(loopHeader)

        curBlock = loopExit
        seal(loopExit)

        return getUnit()
    }

    fun addBr(to: SSABlock): SSABr =
        +SSABr(SSAEdge(curBlock, to), curBlock)

    fun addCondBr(cond: SSAValue, tru: SSABlock, fls: SSABlock) {
        val truEdge = SSAEdge(curBlock, tru)
        val flsEdge = SSAEdge(curBlock,fls)
        +SSACondBr(cond, truEdge, flsEdge, curBlock)
    }

    private fun evalGetObjectValue(irExpr: IrGetObjectValue): SSAValue {
        return +SSAGetObjectValue(typeMapper.map(irExpr.type), curBlock)
    }

    private fun evalReturn(irReturn: IrReturn): SSAValue {
        val retVal = evalExpression(irReturn.value)
        return +SSAReturn(retVal, curBlock)
    }

    private fun evalConstant(irConst: IrConst<*>): SSAConstant = when (irConst.kind) {
        IrConstKind.Null -> SSAConstant.Null
        IrConstKind.Boolean -> SSAConstant.Bool(irConst.value as Boolean)
        IrConstKind.Char -> SSAConstant.Char(irConst.value as Char)
        IrConstKind.Byte -> SSAConstant.Byte(irConst.value as Byte)
        IrConstKind.Short -> SSAConstant.Short(irConst.value as Short)
        IrConstKind.Int -> SSAConstant.Int(irConst.value as Int)
        IrConstKind.Long -> SSAConstant.Long(irConst.value as Long)
        IrConstKind.String -> SSAConstant.String(irConst.value as String)
        IrConstKind.Float -> SSAConstant.Float(irConst.value as Float)
        IrConstKind.Double -> SSAConstant.Double(irConst.value as Double)
    }

    private fun evalGetValue(irGetValue: IrGetValue): SSAValue {
        val declaration = irGetValue.symbol.owner
        return if (declaration in construct.currentDef) {
            construct.readVariable(curBlock, declaration)
        } else {
            println("Failed to find ${declaration.name.asString()} --- $declaration")
            construct.dumpTable()
            TODO("decide how to work with `this` in methods")
        }
    }

    private operator fun <T: SSAInstruction> T.unaryPlus(): T = this.add()

    private fun <T: SSAInstruction> T.add(): T {
        curBlock.body += this
        return this
    }

    fun SSABlock.add(): SSABlock {
        owner.blocks += this
        return this
    }

    private fun evalCall(irCall: IrCall): SSAValue {

        val function = irCall.symbol.owner
        if (function is IrConstructor) return generateConstructorCall(irCall)

        val args = (irCall.getArguments()).map { (_, paramExpr) ->
            evalExpression(paramExpr)
        }

        val callee = declMapper.mapFunction(irCall.symbol.owner)

        if (irCall.dispatchReceiver != null) {
            val receiver = args[0]
            return +SSAMethodCall(receiver, callee, curBlock).apply {
                args.drop(1).forEach { appendOperand(it) }
            }
        }

        return +SSACall(callee, curBlock).apply {
            args.forEach { appendOperand(it) }
        }
    }

    private fun generateConstructorCall(irCall: IrCall): SSAValue {
        val constructor = irCall.symbol.owner as IrConstructor
        val irClass = (irCall.symbol as IrConstructorSymbol).owner.constructedClass

        val ssaClass = SSAClass(irClass)
        val allocationSite = +SSAAlloc(ssaClass, curBlock)

        val args = (irCall.getArguments()).map { (_, paramExpr) ->
            evalExpression(paramExpr)
        }

        val callee = declMapper.mapFunction(constructor)
        +SSAMethodCall(allocationSite, callee, curBlock).apply {
            args.forEach { appendOperand(it) }
        }

        return allocationSite
    }
}