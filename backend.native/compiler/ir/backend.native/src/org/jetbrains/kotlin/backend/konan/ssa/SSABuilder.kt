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

    fun next(): SSABlockId {
        return SSABlockId(current++)
    }
}

class SSAFunctionBuilder(val irFunction: IrFunction, module: SSAModule) {

    val typeMapper = SSATypeMapper()

    val declMapper = SSADeclarationsMapper(module, typeMapper)

    var blockIdGen = SSABlockIdGenerator()

    val name = irFunction.name.asString()
    val type = SSAFuncType(
            typeMapper.map(irFunction.returnType),
            irFunction.valueParameters.map { typeMapper.map(it.type) }
    )
    val func = SSAFunction(name, type)

    var curBlock = func.entry

    // SSA construction-related
    val currentDef = mutableMapOf<IrVariable, MutableMap<SSABlock, SSAValue>>()
    val incompletePhis = mutableMapOf<SSABlock, MutableMap<IrVariable, SSAValue>>()

    private fun writeVariable(block: SSABlock, variable: IrVariable, value: SSAValue) {
        val blocks = currentDef.getOrPut(variable) { mutableMapOf() }
        blocks[block] = value
    }

    private fun readVariable(block: SSABlock, variable: IrVariable): SSAValue =
        if (block in currentDef.getOrPut(variable) { mutableMapOf() }) {
            currentDef[variable]!![block]!!
        } else {
            readRecursiveVariable(block, variable)
        }

    private fun readRecursiveVariable(block: SSABlock, variable: IrVariable): SSAValue {
        val value: SSAValue = when {
            !block.sealed -> {
                val phi = SSAPhi(block).add()
                incompletePhis.getOrPut(block) { mutableMapOf()}[variable] = phi
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

    fun addPhiOperands(variable: IrVariable, phi: SSAPhi): SSAValue {
        for (pred in phi.block.preds) {
            phi.appendOperand(readVariable(pred, variable))
        }
        return tryRemoveTrivialPhi(phi)
    }

    fun tryRemoveTrivialPhi(phi: SSAPhi): SSAValue {
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
        for (use in users) {
            if (use is SSAPhi) {
                tryRemoveTrivialPhi(use)
            }
        }
        return same
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
        writeVariable(curBlock, irVariable, value)
    }

    private fun generateExpression(irExpr: IrExpression): SSAValue = when (irExpr) {
        is IrCall -> generateCall(irExpr)
        is IrGetValue -> generateGetValue(irExpr)
        is IrConst<*> -> generateConstant(irExpr)
        is IrReturn -> generateReturn(irExpr)
        is IrGetObjectValue -> generateGetObjectValue(irExpr)
        is IrWhileLoop -> generateWhileLoop(irExpr)
        is IrSetVariable -> generateSetVariable(irExpr)
        is IrContainerExpression -> generateContainerExpression(irExpr)
        else -> TODO()
    }

    private fun generateSetVariable(setVariable: IrSetVariable): SSAValue {
        val value = generateExpression(setVariable.value)
        writeVariable(curBlock, setVariable.symbol.owner, value)
        return SSAConstant.Undef // TODO: Unit
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
        return SSAConstant.Undef // TODO: Unit
    }

    private fun generateWhileLoop(irLoop: IrWhileLoop): SSAValue {

        val loopHeader = SSABlock(func, SSABlockId(3, "header")).add()
        val loopBody = SSABlock(func, SSABlockId(5, "body")).add()
        val loopExit = SSABlock(func, SSABlockId(4, "exit")).add()

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
        return SSAConstant.Undef // TODO: replace with unit
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
        if (irGetValue.symbol.owner in currentDef) {
            return readVariable(curBlock, irGetValue.symbol.owner as IrVariable)
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
        val callSite = SSACall(callee)
        for ((paramDesc, paramExpr) in irCall.getArguments()) {
            val value = generateExpression(paramExpr)
            callSite.appendOperand(value)
        }
        callSite.add()
        return callSite
    }
}