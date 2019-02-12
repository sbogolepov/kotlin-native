package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

class RefCountPlacementPass : FunctionPass {
    override fun apply(function: SSAFunction) {
        for (block in function.blocks) {
            for (insn in block.body) {
                if (insn is SSAAlloc) {
                    analyzeAllocationSite(insn)
                }
                if (insn is SSACallSite) {
                    analyzeCallSite(insn)
                }
            }
        }
    }

    private fun analyzeCallSite(insn: SSACallSite) {
        val returnType = insn.callee.type.returnType
        if (returnType is ReferenceType)
    }

    private fun analyzeAllocationSite(insn: SSAAlloc) {

    }
}