package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSABr
import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction

class SSARemoveTrampoline() : FunctionPass {
    override val name: String
        get() = "Remove trampoline blocks"

    override fun apply(function: SSAFunction) {
        function.blocks.forEach { block ->
            if (block.body.size != 1) return@forEach
            if (block.body.first() !is SSABr) return@forEach
        }
    }
}