package org.jetbrains.kotlin.backend.konan.ssa.llvm

import llvm.LLVMBasicBlockRef
import llvm.LLVMModuleRef
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.ssa.*

internal class SSAModuleToBitcode(val ssaModule: SSAModule) {
    fun generate(): LLVMModuleRef {
        for (function in ssaModule.functions) {

        }
        return TODO()
    }

}

internal class SSAFuncToBitcode(val func: SSAFunction) {

    val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()

    private fun generateBlock(block: SSABlock) {

    }

    private fun generateInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
        is SSACall -> generateCall(insn)
        is SSAReturn -> generateReturn(insn)
        is SSABr -> generateBr(insn)
        is SSACondBr -> generateCondBr(insn)
        else -> error("Unsupported insntruction: $insn")
    }

    private fun generateCondBr(insn: SSACondBr): LLVMValueRef {
        TODO()
    }

    private fun generateBr(insn: SSABr): LLVMValueRef {
        TODO()
    }

    private fun generateReturn(insn: SSAReturn): LLVMValueRef {
        TODO()
    }

    private fun generateCall(insn: SSACall): LLVMValueRef {
        TODO()
    }
}

