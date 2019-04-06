package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

class RefCountPlacementPass : FunctionPass {

    override val name: String = "Reference counting placement"

    override fun apply(function: SSAFunction) {
        for (block in function.blocks) {
            for (insn in block.body) {
                when (insn) {
                    is SSACallSite -> analyzeCallSite(insn)
                    is SSAAlloc -> analyzeAllocationSite(insn)
                }
            }
        }
    }

    private fun analyzeCallSite(insn: SSACallSite) {

    }

    private fun analyzeAllocationSite(insn: SSAAlloc) {

    }
}