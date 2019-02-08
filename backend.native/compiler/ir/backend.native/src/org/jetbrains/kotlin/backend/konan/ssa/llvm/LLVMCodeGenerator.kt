package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.ContextUtils

internal class LLVMCodeGenerator(
        override val context: Context,
        private  val llvmFn: LLVMValueRef
) : ContextUtils {
    private val builder: LLVMBuilderRef = LLVMCreateBuilder()!!

    fun positionAtEnd(block: LLVMBasicBlockRef) {
        LLVMPositionBuilderAtEnd(builder, block)
    }

    fun br(dest: LLVMBasicBlockRef) = LLVMBuildBr(builder, dest)!!

    fun ret(value: LLVMValueRef?): LLVMValueRef =
            if (value == null) LLVMBuildRetVoid(builder)!! else LLVMBuildRet(builder, value)!!

    fun phi(ty: LLVMTypeRef) = LLVMBuildPhi(builder, ty, "")!!

    fun addIncoming(phi: LLVMValueRef, vararg incoming: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
        memScoped {
            val incomingValues = incoming.map { it.second }.toCValues()
            val incomingBlocks = incoming.map { it.first }.toCValues()

            LLVMAddIncoming(phi, incomingValues, incomingBlocks, incoming.size)
        }
    }

    fun call(callee: LLVMValueRef, args: List<LLVMValueRef>): LLVMValueRef {
        return LLVMBuildCall(builder, callee, args.toCValues(), args.size, "")!!
    }

    fun invoke(callee: LLVMValueRef, args: List<LLVMValueRef>,
               thenBlock: LLVMBasicBlockRef, catchBlock: LLVMBasicBlockRef): LLVMValueRef {
        return LLVMBuildInvoke(builder, callee, args.toCValues(), args.size, thenBlock, catchBlock, "")!!
    }

    fun heapAlloc(type: LLVMTypeRef): LLVMValueRef = TODO()

    fun condBr(condVal: LLVMValueRef, truBlock: LLVMBasicBlockRef, flsBlock: LLVMBasicBlockRef) =
            LLVMBuildCondBr(builder, condVal, truBlock, flsBlock)!!

    fun getParam(paramIndex: Int): LLVMValueRef =
            LLVMGetParam(llvmFn, paramIndex)!!
}