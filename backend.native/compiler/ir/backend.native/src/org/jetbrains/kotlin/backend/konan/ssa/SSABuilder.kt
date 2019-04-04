package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.ir.allParameters
import org.jetbrains.kotlin.backend.konan.ir.constructedClass
import org.jetbrains.kotlin.backend.konan.ir.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.ir.name
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe


private const val DEBUG = true

private fun debug(message: String) { if (DEBUG) println(message) }

class SSABlockIdGenerator {
    fun next(suffix: String): SSABlockId {
        return SSABlockId.Simple(suffix)
    }
}

interface SSAFunctionBuilder {

    var generationContext: GenerationContext

    fun addBr(to: SSABlock): SSABr

    fun addBlock(name: String): SSABlock
}

// TODO: Pass context about class here?
class SSAFunctionBuilderImpl(val func: SSAFunction, val module: SSAModule) : SSAFunctionBuilder {

    override var generationContext: GenerationContext =
            GenerationContext.Function(this, null)

    private val typeMapper = SSATypeMapper()

    private val declMapper = SSADeclarationsMapper(module, typeMapper)

    var blockIdGen = SSABlockIdGenerator()


    var curBlock = func.entry

    private fun SSABlock.addParam(type: SSAType): SSABlockParam =
            SSABlockParam(type, this).also { this.params.add(it) }

    // SSA construction-related
    private val construct = object {
        val currentDef = mutableMapOf<IrValueDeclaration, MutableMap<SSABlock, SSAValue>>()
        val incompleteParams = mutableMapOf<SSABlock, MutableMap<IrValueDeclaration, SSABlockParam>>()

        fun dumpTable() {
            for ((def, map) in currentDef) {
                println("--- ${def.name.asString()} --- $def")
                for ((k, v) in map) {
                    println("${k.id} $v")
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
                    debug("Add param for ${variable.name}")
                    incompleteParams.getOrPut(block) { mutableMapOf() }[variable] = param
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
            return tryRemoveTrivialParam(param)
        }

        private fun tryRemoveTrivialParam(param: SSABlockParam): SSAValue {
                return param
//            val index = param.owner.params.indexOf(param)
//            val values = param.owner.preds.map { edge -> edge.args[index] }
//
//            var same: SSAValue? = null
//
//            for (op in values) {
//                if (op === same || op === param) {
//                    continue // unique value or self reference
//                }
//                if (same != null) {
//                    return param // phi merges at least 2 values -> not trivial
//                }
//                same = op
//            }
//            if (same == null) {
//                same = SSAConstant.Undef // phi is unreachable or in the entry block
//            }
//            param.users.remove(param)
//            val users = param.users
//            param.replaceBy(same)
//            users.filterIsInstance<SSABlockParam>().forEach { tryRemoveTrivialParam(it) }
//            return same
        }
    }

    override fun addBlock(name: String): SSABlock =
            SSABlock(func, blockIdGen.next(name)).add()

    private fun IrType.map() = typeMapper.map(this)

    fun build(irFunction: IrFunction): SSAFunction {

        // TODO: make parameters explicit parameters of the entry block
        irFunction.dispatchReceiverParameter?.let {
            // TODO: Reflect in owner type somehow
            val receiver = SSAReceiver(it.type.map())
            func.dispatchReceiver = receiver
            construct.writeVariable(func.entry, it, receiver)
        }

        irFunction.extensionReceiverParameter?.let {
            // TODO: Reflect in owner type somehow
            val receiver = SSAReceiver(it.type.map())
            func.extensionReceiver = receiver
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
        // We need to populate missing block arguments.
        if (block !in construct.incompleteParams) return
        val incompleteParams = construct.incompleteParams.getValue(block)
        val incomingEdges = block.preds
        for ((variable, param) in incompleteParams) {
            val index = block.params.indexOf(param)
            for (edge in incomingEdges) {
                val arg = construct.readVariable(edge.from, variable)
                edge.args.add(index,arg)
            }
        }
        construct.incompleteParams.remove(block)
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
        val declaration = +SSADeclare(irVariable.name.identifier, value, curBlock)
        construct.writeVariable(curBlock, irVariable, declaration)
    }

    private fun evalExpression(irExpr: IrExpression): SSAValue = when (irExpr) {
        is IrTypeOperatorCall -> evalTypeOperatorCall(irExpr)
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
        is IrTry -> evalTry(irExpr)
        is IrThrow -> evalThrow(irExpr)
        is IrBreak -> evalBreak(irExpr)
        is IrVararg -> evalVararg(irExpr)
        is IrContinue -> evalContinue(irExpr)
        else -> TODO("$irExpr")
    }

    private fun evalTypeOperatorCall(irExpr: IrTypeOperatorCall): SSAValue =
            when (irExpr.operator) {
                IrTypeOperator.CAST                      -> evaluateCast(irExpr)
                IrTypeOperator.IMPLICIT_INTEGER_COERCION -> evaluateIntegerCoercion(irExpr)
                IrTypeOperator.IMPLICIT_CAST             -> evalExpression(irExpr.argument)
                IrTypeOperator.IMPLICIT_NOTNULL          -> TODO(ir2string(irExpr))
                IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                    evalExpression(irExpr.argument)
                    getUnit()
                }
                IrTypeOperator.SAFE_CAST                 -> throw IllegalStateException("safe cast wasn't lowered")
                IrTypeOperator.INSTANCEOF                -> evaluateInstanceOf(irExpr)
                IrTypeOperator.NOT_INSTANCEOF            -> evaluateNotInstanceOf(irExpr)
                IrTypeOperator.SAM_CONVERSION            -> TODO(ir2string(irExpr))
            }

    private fun evaluateCast(irExpr: IrTypeOperatorCall): SSAValue =
            +SSACast(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateIntegerCoercion(irExpr: IrTypeOperatorCall): SSAValue =
            +SSAIntegerCoercion(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateInstanceOf(irExpr: IrTypeOperatorCall): SSAValue =
            +SSAInstanceOf(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateNotInstanceOf(irExpr: IrTypeOperatorCall): SSAValue {
        val instanceOf = evaluateInstanceOf(irExpr)
        return +SSANot(instanceOf, curBlock)
    }

    private fun evalSetField(irExpr: IrSetField): SSAValue {
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        if (irExpr.receiver == null) {
            val value = evalExpression(irExpr.value)
            return +SSASetGlobal(field, value, curBlock)
        } else {
            val receiver = evalExpression(irExpr.receiver!!)
            val value = evalExpression(irExpr.value)
            return +SSASetField(receiver, field, value, curBlock)
        }
    }

    private fun evalGetField(irExpr: IrGetField): SSAValue {
        // Static variables has no receiver
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        return if (irExpr.receiver == null) {
            +SSAGetGlobal(field, curBlock)
        } else {
            val receiver = evalExpression(irExpr.receiver!!)
            +SSAGetField(receiver, field, curBlock)
        }
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

        generationContext.inLoop(irLoop) {
            val loopBody = addBlock("loop_body")

            addBr(loopEntry)

            curBlock = loopEntry
            val condition = evalExpression(irLoop.condition)
            addCondBr(condition, loopBody, loopExit)

            curBlock = loopBody
            seal(loopBody)
            irLoop.body?.let { generateStatement(it) }
            addBr(loopEntry)
            seal(loopEntry)

            curBlock = loopExit
            seal(loopExit)
        }

        return getUnit()
    }

    override fun addBr(to: SSABlock): SSABr =
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

        return if (irCall.dispatchReceiver != null) {
            val receiver = args[0]
            +SSAMethodCall(receiver, callee, curBlock, irCall).apply {
                appendOperands(args.drop(1))
            }
        } else {
            +SSACall(callee, curBlock, irCall).apply {
                appendOperands(args)
            }
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
        +SSAMethodCall(allocationSite, callee, curBlock, irCall).apply {
            appendOperands(args)
        }

        return allocationSite
    }

    // TODO: incorrect.
    private fun evalTry(irTry: IrTry): SSAValue {
        return generationContext.inTryCatch(irTry) {
             evalExpression(irTry.tryResult)
        }
    }

    private fun evalThrow(irThrow: IrThrow): SSAValue {
        val value = evalExpression(irThrow.value)
        generationContext.getTryCatch().emitThrow(value)
        return SSAConstant.Undef
    }

    private fun evalBreak(irBreak: IrBreak): SSAValue {
        generationContext.getLoop().emitBreak(irBreak)
        return SSAConstant.Undef
    }

    private fun evalContinue(irContinue: IrContinue): SSAValue {
        generationContext.getLoop().emitContinue(irContinue)
        return SSAConstant.Undef
    }

    private fun evalVararg(irVararg: IrVararg): SSAValue {
        val values = irVararg.elements.forEach {
            if (it is IrExpression) evalExpression(it)
        }
        return SSAConstant.Undef
    }
}