package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

class InlineAccessorsPass(private val func: SSAFunction) : FunctionPass {
    override fun apply() {
        for (bb in func.blocks) {
            for (insn in bb.body) {
                // TODO: better filter
                if (insn is SSAMethodCall && insn.callee.isTrivialGetter()) {
                    val getter = insn.callee.blocks[0].body[0] as SSAGetField
                    val replacement = SSAGetField(insn.receiver, getter.field)
                    val idx = bb.body.indexOf(insn)
                    bb.body[idx] = replacement
                    insn.replaceBy(replacement)
                }
            }
        }
    }

    private fun SSAFunction.isTrivialGetter() =
            metadata.contains("getter")
                    && blocks.size == 1
                    && blocks[0].body.size == 2
                    && blocks[0].body[0] is SSAGetField
                    && blocks[0].body[1] is SSAReturn
}