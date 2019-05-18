package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType

private const val padDelta = " "

class SSARender(val metaInfoFn: ((SSAInstruction) -> String?)? = null) {

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

    fun render(func: SSAFunction): String {
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

    private val SSAInstruction.slot: Int
        get() = slotTracker.slot(this)

    private fun render(block: SSABlock): String = buildString {
        appendln("block ${renderBlock(block)}(${block.params.joinToString { "%${slotTracker.slot(it)}: ${renderType(it.type)}" }}):")
        pad = padDelta
        for (insn in block.body) {
            appendln(render(insn))
        }
        pad = ""
    }

    private fun renderInsnResult(insn: SSAInstruction) = "%${insn.slot} ${renderType(insn.type)}"

    private fun render(insn: SSAInstruction): String = buildString {
        metaInfoFn?.let {
            val result = it(insn)
            if (result != null) {
                appendln(result)
            }
        }
        append("$pad ")
        append(when (insn) {
            is SSACallSite -> renderCallSite(insn)
            is SSACatch ->      "catch"
            is SSAAlloc ->          "${renderInsnResult(insn)} = allocate"
            is SSAGetField ->       "${renderInsnResult(insn)} = (${renderOperand(insn.receiver)}).${renderOperand(insn.field)}"
            is SSANOP ->            "${renderInsnResult(insn)} = NOP \"${insn.comment}\""
            is SSAGetObjectValue -> "${renderInsnResult(insn)} = GET_OBJECT_VALUE"
            is SSAReturn ->         "ret ${if (insn.retVal != null) renderOperand(insn.retVal!!) else ""}"
            is SSABr ->             "br ${renderOperand(insn.edge)}"
            is SSACondBr ->         "condbr ${renderOperand(insn.condition)} ${renderOperand(insn.truEdge)} ${renderOperand(insn.flsEdge)}"
            is SSASetField ->       "(${renderOperand(insn.receiver)}).${renderOperand(insn.field)} = ${renderOperand(insn.value)}"
            is SSADeclare ->        "${renderInsnResult(insn)} = declare ${insn.name} ${renderOperand(insn.value)}"
            is SSAIncRef -> TODO()
            is SSADecRef -> TODO()
            is SSAInstanceOf ->     "${renderInsnResult(insn)} = ${renderOperand(insn.value)} is ${renderType(insn.typeOperand)}"
            is SSANot ->            "${renderInsnResult(insn)} = not ${renderOperand(insn.value)}"
            is SSACast ->           "${renderInsnResult(insn)} = cast ${renderOperand(insn.value)} to ${renderType(insn.typeOperand)}"
            is SSAIntegerCoercion -> "${renderInsnResult(insn)} = coerce ${renderOperand(insn.value)} to ${renderType(insn.typeOperand)}"
            is SSAGetGlobal ->      "${renderInsnResult(insn)} = get_global ${renderOperand(insn.global)}"
            is SSASetGlobal ->      "set_global ${renderOperand(insn.global)} to ${renderOperand(insn.value)}"
            is SSAThrow ->          "throw ${renderOperand(insn.edge)}"
            is SSAGetITable -> "${renderInsnResult(insn)} = itable ${renderOperand(insn.receiver)} ${insn.callee.name}"
            is SSAGetVTable -> "${renderInsnResult(insn)} = vtable ${renderOperand(insn.receiver)} ${insn.callee.name}"
        })
        insn.comment?.let {
            append("\t\t #$it")
        }
    }

    private fun renderCallSite(insn: SSACallSite): String = buildString {
        fun renderCallSiteArgs(insn: SSACallSite): String {
            val operands = if (insn.receiver == SSAGlobalReceiver) {
                insn.args
            } else {
                insn.operands
            }
            return "${insn.callee.name} ${operands.joinToString { renderOperand(it) }}"
        }

        append(when (insn) {
            is SSAInvoke ->   "${renderInsnResult(insn)} = invoke ${renderCallSiteArgs(insn)} to ${renderOperand(insn.continuation)} except ${renderOperand(insn.exception)}"
            is SSAVirtualCall ->            "${renderInsnResult(insn)} = call_virtual ${renderCallSiteArgs(insn)}"
            is SSAInterfaceCall ->          "${renderInsnResult(insn)} = call_interface ${renderCallSiteArgs(insn)}"
            is SSADirectCall ->             "${renderInsnResult(insn)} = call_direct ${renderCallSiteArgs(insn)}"
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
        is SSAClass -> type.origin.name.asString()
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
