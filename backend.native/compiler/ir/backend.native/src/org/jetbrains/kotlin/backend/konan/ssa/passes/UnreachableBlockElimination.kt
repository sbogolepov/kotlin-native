package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSABlock
import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction

class UnreachableBlockElimination : FunctionPass {
    override val name: String
        get() = "Unreachable block elimination"

    override fun apply(function: SSAFunction) {
        do {
            val orphantBlocks = function.blocks
                    .filter { it != function.entry }
                    .filter { it.preds.isEmpty() }
            orphantBlocks.forEach { removeBlock(function, it) }
        } while (orphantBlocks.isNotEmpty())

    }

    private fun removeBlock(function: SSAFunction, block: SSABlock) {
        block.succs.forEach {
            it.to.preds.remove(it)
        }
        function.blocks.remove(block)
    }
}