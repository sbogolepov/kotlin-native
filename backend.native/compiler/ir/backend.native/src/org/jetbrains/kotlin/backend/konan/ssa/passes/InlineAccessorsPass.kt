package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

class InlineAccessorsPass(private val func: SSAFunction) : FunctionPass {
    override fun apply() {
        for (bb in func.blocks) {
            for (insn in bb.body) {
                // TODO: better filter
                if (insn is SSAMethodCall && insn.callee.isTrivialGetter()) {
                    val getter = insn.callee.blocks[0].body[0] as SSAGetField
                    insn.replaceBy(SSAGetField(insn.receiver, getter.field))
                }
            }
        }
    }

    private fun SSAFunction.isTrivialGetter() =
            name.contains(".<get-")
                    && blocks.size == 1
                    && blocks[0].body.size == 2
                    && blocks[0].body[0] is SSAGetField
                    && blocks[0].body[1] is SSAReturn
}