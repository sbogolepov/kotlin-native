package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.irasdescriptors.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.irasdescriptors.name
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
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

class SSADeclarationsMapper(val module: SSAModule, val typeMapper: SSATypeMapper) {
    fun mapFunction(func: IrFunction): SSAFunction {
        val type = SSAFuncType(
                typeMapper.map(func.returnType),
                func.valueParameters.map { typeMapper.map(it.type) }
        )
        val ssaFunction = SSAFunction(getFqName(func).asString(), type)
        module.imports += ssaFunction
        return ssaFunction
    }
}

class SSAModuleBuilder {

    fun build(irModule: IrModuleFragment): SSAModule {
        val module = SSAModule()
        for (irFile in irModule.files) {
            for (irDeclaration in irFile.declarations) {
                if (irDeclaration is IrFunction) {
                    val func = SSAFunctionBuilder(irDeclaration, module).build()
                    module.functions += func
                }
            }
        }
        return module
    }
}

class SSABlockIdGenerator(var current: Int = 0) {

    fun next(suffix: String): SSABlockId {
        return SSABlockId.Simple(current++, suffix)
    }
}

class SSAFunctionBuilder(val irFunction: IrFunction, module: SSAModule) {

    private val typeMapper = SSATypeMapper()

    private val declMapper = SSADeclarationsMapper(module, typeMapper)

    var blockIdGen = SSABlockIdGenerator()

    val name = irFunction.name.asString()
    val type = SSAFuncType(
            typeMapper.map(irFunction.returnType),
            irFunction.valueParameters.map { typeMapper.map(it.type) }
    )
    val func = SSAFunction(name, type)

    var curBlock = func.entry

    // SSA construction-related
    private val construct = object {
        val currentDef = mutableMapOf<IrVariable, MutableMap<SSABlock, SSAValue>>()
        val incompletePhis = mutableMapOf<SSABlock, MutableMap<IrVariable, SSAValue>>()

        fun writeVariable(block: SSABlock, variable: IrVariable, value: SSAValue) {
            val blocks = currentDef.getOrPut(variable) { mutableMapOf() }
            blocks[block] = value
        }

        fun readVariable(block: SSABlock, variable: IrVariable): SSAValue =
                if (block in currentDef.getOrPut(variable) { mutableMapOf() }) {
                    currentDef[variable]!![block]!!
                } else {
                    readRecursiveVariable(block, variable)
                }

        private fun readRecursiveVariable(block: SSABlock, variable: IrVariable): SSAValue {
            val value: SSAValue = when {
                !block.sealed -> {
                    val phi = SSAPhi(block).add()
                    incompletePhis.getOrPut(block) { mutableMapOf() }[variable] = phi
                    phi
                }
                block.preds.size == 1 -> {
                    readVariable(block.preds.first(), variable)
                }
                // Break potential cycles with operandless phi val
                else -> {
                    val phi = SSAPhi(block).add()
                    writeVariable(block, variable, phi)
                    addPhiOperands(variable, phi)
                }
            }
            writeVariable(block, variable, value)
            return value
        }

        private fun addPhiOperands(variable: IrVariable, phi: SSAPhi): SSAValue {
            for (pred in phi.block.preds) {
                val edge = SSAEdge(pred, curBlock, readVariable(pred, variable))
                phi.appendOperand(edge)
            }
            return tryRemoveTrivialPhi(phi)
        }

        // TODO: is it valid to always replace phi with previous phi?
        private fun tryRemoveTrivialPhi(phi: SSAPhi): SSAValue {
            var same: SSAValue? = null

            for (op in phi.operands) {
                if (op === same || op === phi) {
                    continue // unique value or self reference
                }
                if (same != null) {
                    return phi // phi merges at least 2 values -> not trivial
                }
                same = op
            }
            if (same == null) {
                same = SSAConstant.Undef // phi is unreachable or in the entry block
            }
            phi.users.remove(phi)
            val users = phi.users
            phi.replaceBy(same)
            users.filterIsInstance<SSAPhi>().forEach { tryRemoveTrivialPhi(it) }
            return same
        }
    }

    private fun addBlock(name: String = ""): SSABlock {
        return SSABlock(func, blockIdGen.next(name)).add()
    }

    fun build(): SSAFunction {
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
        is IrExpressionBody -> TODO()
        is IrSyntheticBody -> TODO()
        else -> TODO()
    }

    private fun generateStatement(irStmt: IrStatement) {
        when (irStmt) {
            is IrExpression -> generateExpression(irStmt)
            is IrVariable -> generateVariable(irStmt)
            else -> TODO()
        }
    }

    private fun generateVariable(irVariable: IrVariable) {
        val value = irVariable.initializer?.let { generateExpression(it) }
                // TODO: explain
                ?: SSAConstant.Undef
        construct.writeVariable(curBlock, irVariable, value)
    }

    private fun generateExpression(irExpr: IrExpression): SSAValue = when (irExpr) {
        is IrCall -> generateCall(irExpr)
        is IrGetValue -> generateGetValue(irExpr)
        is IrConst<*> -> generateConstant(irExpr)
        is IrReturn -> generateReturn(irExpr)
        is IrGetObjectValue -> generateGetObjectValue(irExpr)
        is IrWhileLoop -> generateWhileLoop(irExpr)
        is IrWhen -> generateWhen(irExpr)
        is IrSetVariable -> generateSetVariable(irExpr)
        is IrContainerExpression -> generateContainerExpression(irExpr)
        else -> TODO()
    }

    private fun generateWhen(irWhen: IrWhen): SSAValue =
        WhenGenerator(irWhen).generate()

    private inner class WhenGenerator(val irWhen: IrWhen) {

        private val exitBlock = SSABlock(func, blockIdGen.next("when_exit"))

        private val phi: SSAPhi by lazy {
            SSAPhi(exitBlock).also {
                exitBlock.body += it
            }
        }

        private val isExpression = isUnconditional(irWhen.branches.last()) && !irWhen.type.isUnit()

        private fun isUnconditional(branch: IrBranch): Boolean =
                branch.condition is IrConst<*>                            // If branch condition is constant.
                        && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

        // TODO: common exit block
        //  if-expression
        private fun generateWhenCase(branch: IrBranch, isLast: Boolean) {
            val nextBlock = if (isLast) exitBlock else SSABlock(func, blockIdGen.next("when_cond"))
            val result = if (isUnconditional(branch)) {
                seal(curBlock)
                generateExpression(branch.result)
            } else {
                seal(curBlock)
                val cond = generateExpression(branch.condition)
                val bodyBlock = addBlock("when_body")
                SSACondBr(cond, bodyBlock, nextBlock).add()
                curBlock = bodyBlock
                seal(curBlock)
                generateExpression(branch.result)
            }

            if (curBlock.body.isEmpty() || !curBlock.body.last().isTerminal()) {
                SSABr(exitBlock).add()
                if (isExpression) {
                    phi.appendOperand(SSAEdge(curBlock, exitBlock, result))
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
                phi
            } else {
                getUnit()
            }
        }
    }

    private fun getUnit(): SSAValue = SSAConstant.Undef

    private fun generateSetVariable(setVariable: IrSetVariable): SSAValue {
        val value = generateExpression(setVariable.value)
        construct.writeVariable(curBlock, setVariable.symbol.owner, value)
        return getUnit()
    }

    private fun generateContainerExpression(containerExpr: IrContainerExpression): SSAValue {
        containerExpr.statements.dropLast(1).forEach { generateStatement(it) }
        containerExpr.statements.lastOrNull()?.let {
            if (it is IrExpression) {
                return generateExpression(it)
            } else {
                generateStatement(it)
            }
        }
        return getUnit()
    }

    private fun generateWhileLoop(irLoop: IrWhileLoop): SSAValue {

        val loopHeader = addBlock("loop_header")
        val loopBody = addBlock("loop_body")
        val loopExit = addBlock("loop_exit")

        SSABr(loopHeader).add()

        curBlock = loopHeader
        val condition = generateExpression(irLoop.condition)
        SSACondBr(condition, loopBody, loopExit).add()

        curBlock = loopBody
        seal(loopBody)
        irLoop.body?.let { generateStatement(it) }
        SSABr(loopHeader).add()
        seal(loopHeader)

        curBlock = loopExit
        seal(loopExit)
        return SSAConstant.Undef // TODO: Unit
    }

    private fun generateGetObjectValue(irExpr: IrGetObjectValue): SSAValue {
        return SSAGetObjectValue().add()
    }

    private fun generateReturn(irReturn: IrReturn): SSAValue {
        val retVal = generateExpression(irReturn.value)
        return SSAReturn(retVal).add()
    }

    private fun generateConstant(irConst: IrConst<*>): SSAConstant = when (irConst.kind) {
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

    private fun generateGetValue(irGetValue: IrGetValue): SSAValue {
        if (irGetValue.symbol.owner in construct.currentDef) {
            return construct.readVariable(curBlock, irGetValue.symbol.owner as IrVariable)
        } else {
            TODO("Unsupported operation")
        }
    }

    private fun SSABlock.edgeTo(target: SSABlock) {
        this.succs += target
        target.preds += this
    }

    fun <T: SSAInstruction> T.add(): T {
        when (this) {
            is SSABr -> curBlock.edgeTo(target)
            is SSACondBr -> {
                curBlock.edgeTo(truTarget)
                curBlock.edgeTo(flsTarget)
            }
        }
        curBlock.body += this
        return this
    }

    fun SSABlock.add(): SSABlock {
        func.blocks += this
        return this
    }

    private fun generateCall(irCall: IrCall): SSAValue {
        val callee = declMapper.mapFunction(irCall.symbol.owner)
        return SSACall(callee).apply {
            for ((paramDesc, paramExpr) in irCall.getArguments()) {
                val value = generateExpression(paramExpr)
                appendOperand(value)
            }
            add()
        }
    }
}