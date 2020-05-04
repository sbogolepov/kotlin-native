package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.CGNode

class ReferenceCountingOperationsPlacementPass(
        private val escapeAnalysisResults: Map<SSAFunction, Map<SSAValue, CGNode>>
) : FunctionPass {

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
        insn.args.forEach { arg ->
            when (arg.type) {
                is ReferenceType -> {
                    // Increment ref before call
                    // And decrement after
                }
            }
        }
    }

    private fun analyzeAllocationSite(insn: SSAAlloc) {

    }

}