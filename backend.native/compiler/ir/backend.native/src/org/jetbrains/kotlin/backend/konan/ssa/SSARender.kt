package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType

private const val padDelta = " "

class SSARender() {

    private var pad = ""

    val slotTracker = SSASlotTracker()

    fun render(func: SSAFunction): String = buildString {
            func.params.forEach {
                slotTracker.track(it)
            }
            appendln("${func.name}(${func.params.joinToString { renderOperand(it) }}): ${renderType(func.type)}")
            for (block in func.blocks) {
                for (param in block.params) {
                    slotTracker.track(param)
                }
                for (insn in block.body) {
                    slotTracker.track(insn)
                }
                appendln(render(block))
            }
        }

    private fun render(block: SSABlock): String = buildString {
        appendln("block ${block.id}(${block.params.joinToString { "%${slotTracker.slot(it)}: ${renderType(it.type)}" }})")
        pad = padDelta
        for (insn in block.body) {
            appendln(render(insn))
        }
        pad = ""
    }

    private fun render(insn: SSAInstruction): String = buildString {

        val track = slotTracker.slot(insn)

        when (insn) {
            is SSACall -> append("$pad %$track: ${renderType(insn.type)} = call ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }}")
            is SSAMethodCall -> append("$pad %$track: ${renderType(insn.type)} = call (${renderOperand(insn.operands[0])}).${insn.callee.name} ${insn.operands.drop(1).joinToString { renderOperand(it) }}")
            is SSAAlloc -> append("$pad %$track: ${renderType(insn.type)} = allocate")
            is SSAGetObjectValue -> append("$pad %$track = GET OBJECT VALUE")
            is SSAReturn -> append("$pad ret ${renderOperand(insn.retVal)}")
            is SSABr -> append("$pad br ${renderOperand(insn.edge)}")
            is SSACondBr -> append("$pad condbr ${renderOperand(insn.condition)} ${renderOperand(insn.truEdge)} ${renderOperand(insn.flsEdge)}")
            is SSAGetField -> append("$pad %$track: ${renderType(insn.type)} = (${renderOperand(insn.receiver)}).${renderOperand(insn.field)}")
            is SSASetField -> append("$pad (${renderOperand(insn.receiver)}).${renderOperand(insn.field)} = ${renderOperand(insn.value)}")
            is SSANOP -> append("$pad %$track: ${renderType(insn.type)} = NOP \"${insn.comment}\"")
            else -> append("$pad UNSUPPORTED")
        }
    }

    private fun renderOperand(value: SSAValue): String = when {
        value is SSAReceiver -> "this"
        value is SSAConstant -> "${renderConstant(value)}: ${renderType(value.type)}"
        value is SSABlock -> "${value.id}"
        value is SSAEdge -> "${value.to.id}(${value.args.joinToString { renderOperand(it) }})"
        value is SSAField -> "${value.name}: ${renderType(value.type)}"
        slotTracker.isTracked(value) -> "%${slotTracker.slot(value)}: ${renderType(value.type)}"
        else -> "UNNAMED"
    }

    private fun renderType(type: SSAType): String = when (type) {
        is SSAClass -> type.irClass.name.asString()
        is SSAPrimitiveType -> type.name.toLowerCase()
        is SSAWrapperType -> "wrap(${renderIrType(type.irType)})"
        is SSAFuncType -> "(${type.parameterTypes.joinToString { renderType(it) }}) -> ${renderType(type.returnType)}"
        else -> "type_unk"
    }

    private fun renderIrType(irType: IrType): String =
            if (irType is IrSimpleType) {
                irType.classifier.descriptor.name.asString()
            } else {
                irType.toString()
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
