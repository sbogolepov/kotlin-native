package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType

private const val padDelta = " "

class SSARender {

    fun render(module: SSAModule): String = buildString {
        appendln("--- imports")
        module.imports.forEach {
            appendln(renderFuncHeader(it))
        }
        appendln("--- declarations")
        module.functions.forEach {
            appendln(render(it))
        }
    }

    private fun renderFuncHeader(func: SSAFunction): String =
            "${func.name}(${func.params.joinToString { renderOperand(it) }}): ${renderType(func.type)}"

    private var pad = ""

    private val slotTracker = SSASlotTracker()

    private val blockTracker = SSASlotTracker()

    private fun renderBlock(block: SSABlock) =
            "${block.id}${blockTracker.slot(block)}"

    private fun render(func: SSAFunction): String {
        slotTracker.clear()
        blockTracker.clear()
        func.params.forEach {
            slotTracker.track(it)
        }
        for (block in func.blocks) {
            blockTracker.track(block)
            for (param in block.params) {
                slotTracker.track(param)
            }
            for (insn in block.body) {
                slotTracker.track(insn)
            }

        }
        return buildString {
            appendln(renderFuncHeader(func))
            for (block in func.blocks) {
                appendln(render(block))
            }
        }
    }

    private fun render(block: SSABlock): String = buildString {
        appendln("block ${renderBlock(block)}(${block.params.joinToString { "%${slotTracker.slot(it)}: ${renderType(it.type)}" }}):")
        pad = padDelta
        for (insn in block.body) {
            appendln(render(insn))
        }
        pad = ""
    }

    private fun render(insn: SSAInstruction): String = buildString {
        val track = slotTracker.slot(insn)
        append("$pad ")
        append(when (insn) {
            is SSACallSite -> renderCallSite(insn)
            is SSAAlloc ->      "%$track: ${renderType(insn.type)} = allocate"
            is SSAGetField ->   "%$track: ${renderType(insn.type)} = (${renderOperand(insn.receiver)}).${renderOperand(insn.field)}"
            is SSANOP ->        "%$track: ${renderType(insn.type)} = NOP \"${insn.comment}\""
            is SSAGetObjectValue -> "%$track ${renderType(insn.type)} = GET OBJECT VALUE"
            is SSAReturn ->     "ret ${if (insn.retVal != null) renderOperand(insn.retVal!!) else ""}"
            is SSABr ->         "br ${renderOperand(insn.edge)}"
            is SSACondBr ->     "condbr ${renderOperand(insn.condition)} ${renderOperand(insn.truEdge)} ${renderOperand(insn.flsEdge)}"
            is SSASetField -> "(${renderOperand(insn.receiver)}).${renderOperand(insn.field)} = ${renderOperand(insn.value)}"
            else -> "UNSUPPORTED"
        })
    }

    private fun renderCallSite(insn: SSACallSite): String = buildString {
        val track = slotTracker.slot(insn)
        append(when (insn) {
            is SSACall, is SSAMethodCall -> "%$track: ${renderType(insn.type)} = call ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }}"
            is SSAInvoke -> "invoke ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }} to ${renderOperand(insn.continuation)} except ${renderOperand(insn.exception)}"
            is SSAMethodInvoke -> "invoke ${insn.callee.name} ${insn.operands.joinToString { renderOperand(it) }} to ${renderOperand(insn.continuation)} except ${renderOperand(insn.exception)}"
            else -> error("Unknown callSite type: $insn")
        })
    }

    private fun renderOperand(value: SSAValue): String = when {
        value is SSAReceiver -> "this"
        value is SSAConstant -> "${renderConstant(value)}: ${renderType(value.type)}"
        value is SSABlock -> renderBlock(value)
        value is SSAEdge -> "${renderBlock(value.to)}(${value.args.joinToString { renderOperand(it) }})"
        value is SSAField -> "${value.name}: ${renderType(value.type)}"
        slotTracker.isTracked(value) -> "%${slotTracker.slot(value)}: ${renderType(value.type)}"
        else -> "UNNAMED $value"
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
