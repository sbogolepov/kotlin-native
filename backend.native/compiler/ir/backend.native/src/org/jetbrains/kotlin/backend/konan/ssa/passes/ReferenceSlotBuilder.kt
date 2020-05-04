package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.CGNode
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.EscapeState

class Slots(
        val allocs: List<SSAAlloc>
)

class ReferenceSlotBuilder(
        private val functionToSlots: MutableMap<SSAFunction, Slots>,
        private val escapeAnalysisResults: Map<SSAFunction, Map<SSAValue, CGNode>>
) : FunctionPass {
    override val name: String = "Reference Slot Building"

    override fun apply(function: SSAFunction) {
        val escapeResults = escapeAnalysisResults[function]
                ?: emptyMap()
        val allocs = function.blocks
                .flatMap(SSABlock::body)
                .filterIsInstance<SSAAlloc>()
                .filter { escapeResults[it]?.let {
                    it.escapeState != EscapeState.Local  } == true
                }
        functionToSlots[function] = Slots(allocs)
    }
}