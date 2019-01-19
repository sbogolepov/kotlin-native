package org.jetbrains.kotlin.backend.konan.ssa

private const val padDelta = " "

class SSARender() {

    private var pad = ""

    val slotTracker = SSASlotTracker()

    fun render(func: SSAFunction): String = buildString {
            for (insn in func.entry.body) {
                slotTracker.track(insn)
            }
            appendln(func.name)
            appendln(render(func.entry))
        }

    fun render(block: SSABlock): String = buildString {
        appendln("block ${block.id}")
        pad = padDelta
        for (insn in block.body) {
            appendln(render(insn))
        }
        pad = ""
    }

    private fun render(insn: SSAInstruction): String = buildString {
        when (insn) {
            is SSACall -> append("$pad %${slotTracker.slot(insn)} = CALL ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }}")
            is SSAReturn -> append("$pad RET")
            else -> append("$pad UNSUPPORTED")
        }
    }

    private fun renderOperand(value: SSAValue): String = when {
        value is SSAConstant -> renderConstant(value)
        slotTracker.isTracked(value) -> "%${slotTracker.slot(value)}"
        else -> "UNNAMED"
    }

    private fun renderConstant(const: SSAConstant): String = when (const) {
        SSAConstant.Undef -> "undef"
        SSAConstant.Null -> "null"
        is SSAConstant.Bool -> if (const.value) "true" else "false"
        is SSAConstant.Byte -> const.value.toString()
        is SSAConstant.Char -> const.value.toString()
        is SSAConstant.Int -> const.value.toString()
        is SSAConstant.Long -> const.value.toString()
        is SSAConstant.Float -> const.value.toString()
        is SSAConstant.Double -> const.value.toString()
        is SSAConstant.String -> "\"${const.value}\""
        else -> error("Unsupported constant type: $const")
    }
}
