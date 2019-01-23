package org.jetbrains.kotlin.backend.konan.ssa

private const val padDelta = " "

class SSARender() {

    private var pad = ""

    val slotTracker = SSASlotTracker()

    fun render(func: SSAFunction): String = buildString {
            appendln("${func.name}(${func.params.joinToString { it.name }})")
            for (block in func.blocks) {
                for (insn in block.body) {
                    slotTracker.track(insn)
                }
                appendln(render(block))
            }
        }

    private fun render(block: SSABlock): String = buildString {
        appendln("block ${block.id}")
        pad = padDelta
        for (insn in block.body) {
            appendln(render(insn))
        }
        pad = ""
    }

    private fun render(insn: SSAInstruction): String = buildString {

        val track = slotTracker.slot(insn)

        when (insn) {
            is SSACall -> append("$pad %$track = call ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }}")
            is SSAGetObjectValue -> append("$pad %$track = GET OBJECT VALUE")
            is SSAReturn -> append("$pad ret ${renderOperand(insn.retVal)}")
            is SSABr -> append("$pad br ${renderOperand(insn.target)}")
            is SSAPhi -> append("$pad %$track = phi ${insn.operands.joinToString { renderOperand(it) }}")
            is SSACondBr -> append("$pad condbr ${renderOperand(insn.condition)} ${renderOperand(insn.truTarget)} ${renderOperand(insn.flsTarget)}")
            else -> append("$pad UNSUPPORTED")
        }
    }

    private fun renderOperand(value: SSAValue): String = when {
        value is SSAConstant -> renderConstant(value)
        value is SSABlock -> "${value.id}"
        value is SSAEdge -> "{${value.from.id}: ${renderOperand(value.value)}}"
        slotTracker.isTracked(value) -> "%${slotTracker.slot(value)}"
        else -> "UNNAMED"
    }

    private fun renderConstant(const: SSAConstant): String = when (const) {
        SSAConstant.Undef       -> "undef"
        SSAConstant.Null        -> "null"
        is SSAConstant.Bool     -> if (const.value) "true" else "false"
        is SSAConstant.Byte     -> const.value.toString()
        is SSAConstant.Char     -> const.value.toString()
        is SSAConstant.Int      -> const.value.toString()
        is SSAConstant.Long     -> const.value.toString()
        is SSAConstant.Float    -> const.value.toString()
        is SSAConstant.Double   -> const.value.toString()
        is SSAConstant.String   -> "\"${const.value}\""
        else                    -> error("Unsupported constant type: $const")
    }
}
