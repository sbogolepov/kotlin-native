package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType

private const val padDelta = " "

fun dotGraph(name: String, nodes: List<String>, edges: List<Pair<String, String>>): String = buildString {
    appendln("digraph \"${name}\" {")
    nodes.map { "$it;" }.forEach(this::appendln)
    edges.map { "${it.first} -> ${it.second};" }.forEach(this::appendln)
    appendln("}")
}

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

    private fun prepareRendererState(function: SSAFunction) {
        slotTracker.clear()
        blockTracker.clear()
        function.params.forEach {
            slotTracker.track(it)
        }
        for (block in function.blocks) {
            blockTracker.track(block)
            for (param in block.params) {
                slotTracker.track(param)
            }
            for (insn in block.body) {
                slotTracker.track(insn)
            }
        }
    }

    private val SSABlock.nameWithId: String
        get() = "${id}${blockTracker.slot(this)}"

    fun renderFunctionAsDot(function: SSAFunction): String {
        prepareRendererState(function)
        return dotGraph(
            renderFuncHeader(function),
            function.blocks.map { block -> "${block.nameWithId} [shape=box label=\"${renderBlockAsDot(block)}\"];" },
            function.blocks.flatMap { it.succs.map { it.from.nameWithId to it.to.nameWithId } }
        )
    }

    private fun renderBlockAsDot(block: SSABlock): String = buildString {
        append("block ${block.nameWithId}(${block.params.joinToString { "%${slotTracker.slot(it)}: ${renderType(it.type)}" }}):")
        append("\\l")
        for (insn in block.body) {
            append(render(insn))
            append("\\l")
        }
    }

    private fun renderFuncHeader(func: SSAFunction): String =
            "${func.name}(${func.params.joinToString { renderOperand(it) }}): ${renderType(func.type)}"

    private var pad = ""

    private val slotTracker = SSASlotTracker()

    private val blockTracker = SSASlotTracker()

    private fun renderBlock(block: SSABlock) = block.nameWithId

    fun render(function: SSAFunction): String {
        prepareRendererState(function)
        return buildString {
            appendln(renderFuncHeader(function))
            for (block in function.blocks) {
                appendln(render(block))
            }
        }
    }

    private val SSAInstruction.slot: Int
        get() = slotTracker.slot(this)

    private fun render(block: SSABlock): String = buildString {
        appendln("block ${block.nameWithId}(${block.params.joinToString { "%${slotTracker.slot(it)}: ${renderType(it.type)}" }}):")
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
            is SSABr ->             "go ${renderOperand(insn.edge)}"
            is SSACondBr ->         "if ${renderOperand(insn.condition)} go ${renderOperand(insn.truEdge)} else go ${renderOperand(insn.flsEdge)}"
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
            return "${insn.callee.name} (${insn.operands.joinToString { renderOperand(it) }})"
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

private class BlockContentsRenderer() {
    private fun render(block: SSABlock) {

    }
}
