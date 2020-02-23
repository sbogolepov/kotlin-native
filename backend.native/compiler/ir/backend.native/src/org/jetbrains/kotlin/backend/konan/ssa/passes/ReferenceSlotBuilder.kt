package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAAlloc
import org.jetbrains.kotlin.backend.konan.ssa.SSABlock
import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction

class Slots(
        val allocs: List<SSAAlloc>
)

class ReferenceSlotBuilder(
        private val functionToSlots: MutableMap<SSAFunction, Slots>
) : FunctionPass {
    override val name: String = "Reference Slot Building"

    override fun apply(function: SSAFunction) {
        val allocs = function.blocks
                .flatMap(SSABlock::body)
                .filterIsInstance<SSAAlloc>()

        functionToSlots[function] = Slots(allocs)
    }
}