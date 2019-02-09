package org.jetbrains.kotlin.backend.konan.ssa.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isComparisonDescriptor
import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.llvm.Runtime
import org.jetbrains.kotlin.backend.konan.llvm.isFloatingPoint
import org.jetbrains.kotlin.backend.konan.llvm.kNullObjHeaderPtr
import org.jetbrains.kotlin.backend.konan.llvm.type
import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall

internal class LLVMModuleFromSSA(val context: Context, val ssaModule: SSAModule) {

    private val llvmModule = LLVMModuleCreateWithName(ssaModule.name)!!
    private val target = context.config.target
    private val runtimeFile = context.config.distribution.runtime(target)
    val runtime = Runtime(runtimeFile)

    private val typeMapper = LLVMTypeMapper(runtime)

    private val llvmDeclarations = LLVMDeclarationsBuilder(ssaModule, llvmModule, typeMapper).build()

    fun generate(): LLVMModuleRef {
        LLVMSetDataLayout(llvmModule, runtime.dataLayout)
        LLVMSetTarget(llvmModule, runtime.target)

        for (function in ssaModule.functions) {
            LLVMFunctionFromSSA(context, function, llvmDeclarations, typeMapper).generate()
        }
        return llvmModule
    }
}

private class LLVMFunctionFromSSA(
        val context: Context,
        val ssaFunc: SSAFunction,
        val llvmDeclarations: LLVMDeclarations,
        val typeMapper: LLVMTypeMapper
) {

    private val llvmFunc = llvmDeclarations.functions.getValue(ssaFunc)

    private val paramIndex = ssaFunc.params.mapIndexed { index, argument -> argument to index }.toMap()

    private val codegen = LLVMCodeGenerator(context, llvmFunc)

    val constTrue = LLVMConstInt(LLVMInt1Type(), 1, 1)!!

    private val intrinsicGenerator = IntrinsicGenerator(object : IntrinsicGeneratorEnvironment {
        override val codegen = this@LLVMFunctionFromSSA.codegen
    })

    private fun SSAType.map() = typeMapper.map(this)

    val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()
    val blockParamToPhi = mutableMapOf<SSABlockParam, LLVMValueRef>()

    fun generate(): LLVMValueRef {
        for (block in ssaFunc.blocks) {
            val bb = LLVMAppendBasicBlock(llvmFunc, block.id.toString())!!
            blocksMap[block] = bb
            codegen.positionAtEnd(bb)
            block.params.forEach {
                blockParamToPhi[it] = codegen.phi(it.type.map())
            }
        }
        for (block in ssaFunc.blocks) {
            generateBlock(block)
        }
        return llvmFunc
    }

    private fun generateBlock(block: SSABlock) {
        val bb = blocksMap.getValue(block)
        codegen.positionAtEnd(bb)
        for (insn in block.body) {
            emitValue(insn)
        }
    }

    private val valueCache = mutableMapOf<SSAValue, LLVMValueRef>()

    private fun emitValue(value: SSAValue): LLVMValueRef = valueCache.getOrPut(value) {
        when (value) {
            is SSAConstant -> emitConstant(value)
            is SSAInstruction -> emitInstruction(value)
            is SSAFuncArgument -> emitFuncArgument(value)
            is SSABlockParam -> emitBlockParam(value)
            else -> error("Unsupported value type $value")
        }
    }

    private fun emitBlockParam(value: SSABlockParam): LLVMValueRef =
            blockParamToPhi.getValue(value)

    private fun emitFuncArgument(value: SSAFuncArgument): LLVMValueRef =
            codegen.getParam(paramIndex.getValue(value))

    private fun emitConstant(value: SSAConstant): LLVMValueRef = when (value) {
        SSAConstant.Undef -> TODO()
        SSAConstant.Null -> codegen.kNullObjHeaderPtr
        is SSAConstant.Bool -> when (value.value) {
            true -> {
                constTrue
            }
            false -> LLVMConstInt(LLVMInt1Type(), 0, 1)!!
        }
        is SSAConstant.Byte -> LLVMConstInt(LLVMInt8Type(), value.value.toLong(), 1)!!
        is SSAConstant.Char -> LLVMConstInt(LLVMInt16Type(), value.value.toLong(), 0)!!
        is SSAConstant.Short -> LLVMConstInt(LLVMInt16Type(), value.value.toLong(), 1)!!
        is SSAConstant.Int -> LLVMConstInt(LLVMInt32Type(), value.value.toLong(), 1)!!
        is SSAConstant.Long -> LLVMConstInt(LLVMInt64Type(), value.value, 1)!!
        is SSAConstant.Float -> LLVMConstRealOfString(LLVMFloatType(), value.value.toString())!!
        is SSAConstant.Double -> LLVMConstRealOfString(LLVMDoubleType(), value.value.toString())!!
        is SSAConstant.String -> TODO("String constants are not implemented")
    }

    private fun emitInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
        is SSACallSite -> emitCallSite(insn)
        is SSAReturn -> emitReturn(insn)
        is SSABr -> emitBr(insn)
        is SSACondBr -> emitCondBr(insn)
        is SSAAlloc -> emitAlloc(insn)
        else -> error("Unsupported instruction: $insn")
    }

    private fun emitCallSite(callSite: SSACallSite): LLVMValueRef {
        val function = callSite.irOrigin.symbol.owner
        return when {
            function.origin == IrDeclarationOrigin.IR_BUILTINS_STUB -> {
                val args = callSite.operands.map { emitValue(it) }
                evaluateOperatorCall(function, args)
            }
            function.isTypedIntrinsic -> {
                val args = callSite.operands.map { emitValue(it) }
                intrinsicGenerator.evaluateCall(callSite, args)
            }
            else -> when (callSite) {
                is SSACall -> emitCall(callSite)
                is SSAInvoke -> emitInvoke(callSite)
                is SSAMethodCall -> emitMethodCall(callSite)
                is SSAMethodInvoke -> emitMethodInvoke(callSite)
            }
        }
    }

    private fun evaluateOperatorCall(function: IrFunction, args: List<LLVMValueRef>): LLVMValueRef {
        val ib = context.irModule!!.irBuiltins

        with(codegen) {
            return when {
                function == ib.eqeqeqFun -> icmpEq(args[0], args[1])
                function == ib.booleanNotFun -> icmpNe(args[0], constTrue)

                function.isComparisonDescriptor(ib.greaterFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpGt(args[0], args[1])
                    else icmpGt(args[0], args[1])
                }
                function.isComparisonDescriptor(ib.greaterOrEqualFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpGe(args[0], args[1])
                    else icmpGe(args[0], args[1])
                }
                function.isComparisonDescriptor(ib.lessFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpLt(args[0], args[1])
                    else icmpLt(args[0], args[1])
                }
                function.isComparisonDescriptor(ib.lessOrEqualFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpLe(args[0], args[1])
                    else icmpLe(args[0], args[1])
                }
                else -> error(function.name.toString())
            }
        }
    }

    private fun emitAlloc(insn: SSAAlloc): LLVMValueRef {
        return codegen.heapAlloc(insn.type.map())
    }

    private fun emitCondBr(insn: SSACondBr): LLVMValueRef {
        mapArgsToPhis(insn.truEdge)
        mapArgsToPhis(insn.flsEdge)
        val cond = emitValue(insn.condition)
        val truDest = blocksMap.getValue(insn.truEdge.to)
        val flsDest = blocksMap.getValue(insn.flsEdge.to)
        return codegen.condBr(cond, truDest, flsDest)
    }

    private fun emitBr(insn: SSABr): LLVMValueRef {
        mapArgsToPhis(insn.edge)
        val dest = blocksMap.getValue(insn.edge.to)
        return codegen.br(dest)
    }

    private fun emitReturn(insn: SSAReturn): LLVMValueRef {
        val retval = insn.retVal?.let {
            emitValue(it)
        }
        return codegen.ret(retval)
    }

    private fun emitCall(insn: SSACall): LLVMValueRef {
        val callee = llvmDeclarations.functions.getValue(insn.callee)
        val args = insn.operands.map { emitValue(it) }
        return codegen.call(callee, args)
    }

    private fun emitMethodCall(insn: SSAMethodCall): LLVMValueRef {
        val callee = llvmDeclarations.functions.getValue(insn.callee)
        val args = insn.operands.map { emitValue(it) }
        return codegen.call(callee, args)
    }

    private fun emitInvoke(insn: SSAInvoke): LLVMValueRef {
        val callee = llvmDeclarations.functions.getValue(insn.callee)
        val args = insn.operands.map { emitValue(it) }
        mapArgsToPhis(insn.continuation)
        mapArgsToPhis(insn.exception)
        val thenBlock = blocksMap.getValue(insn.continuation.to)
        val catchBlock = blocksMap.getValue(insn.exception.to)
        return codegen.invoke(callee, args, thenBlock, catchBlock)
    }

    private fun emitMethodInvoke(insn: SSAMethodInvoke): LLVMValueRef {
        val callee = llvmDeclarations.functions.getValue(insn.callee)
        val args = insn.operands.map { emitValue(it) }
        mapArgsToPhis(insn.continuation)
        mapArgsToPhis(insn.exception)
        val thenBlock = blocksMap.getValue(insn.continuation.to)
        val catchBlock = blocksMap.getValue(insn.exception.to)
        return codegen.invoke(callee, args, thenBlock, catchBlock)
    }

    private fun mapArgsToPhis(edge: SSAEdge) {
        val src = blocksMap.getValue(edge.from)
        edge.args.forEachIndexed { idx, value ->
            val blockParam = edge.to.params[idx]
            val phi = blockParamToPhi.getValue(blockParam)
            codegen.addIncoming(phi, src to emitValue(value))
        }
    }
}

