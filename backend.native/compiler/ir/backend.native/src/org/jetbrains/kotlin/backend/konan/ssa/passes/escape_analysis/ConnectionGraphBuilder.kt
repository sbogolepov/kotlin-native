package org.jetbrains.kotlin.backend.konan.ssa.passes.escape_analysis

import org.jetbrains.kotlin.backend.konan.ssa.*

class ConnectionGraph(

)

class ConnectionGraphBuilder(val function: SSAFunction) {


    private val parameters = mutableMapOf<SSAFuncArgument, CGReferenceVariable>()

    private val referenceVariables = mutableMapOf<SSAValue, CGReferenceVariable>()

    private val objectNodes = mutableListOf<CGObjectNode>()

    fun build(): ConnectionGraph {
        function.params.filter { it.type is ReferenceType }.forEach {
            parameters[it] = CGReferenceVariable(it.id, it)
                    .apply { escapeState = EscapeState.ESCAPE }
        }
        for (block in function.blocks) {
            processBlock(block)
        }

        return ConnectionGraph()
    }

    // TODO: process params
    private fun processBlock(block: SSABlock) {
        for (insn in block.body) {
            processInstruction(insn)
        }
    }

    private fun processInstruction(insn: SSAInstruction) = when (insn) {
        is SSADeclare -> {}
        is SSAIncRef -> {}
        is SSADecRef -> {}
        is SSANOP -> {}
        is SSADirectCall -> processCallSite(insn)
        is SSAInvoke -> processCallSite(insn)
        is SSAVirtualCall -> processCallSite(insn)
        is SSAInterfaceCall -> processCallSite(insn)
        is SSAGetITable -> {}
        is SSAGetVTable -> {}
        is SSABr -> {}
        is SSACondBr -> {}
        is SSAReturn -> processReturn(insn)
        is SSAAlloc -> processAlloc(insn)
        is SSAGetField -> {}
        is SSASetField -> {}
        is SSAGetGlobal -> {}
        is SSASetGlobal -> {}
        is SSAGetObjectValue -> {}
        is SSACatch -> {}
        is SSAInstanceOf -> {}
        is SSACast -> {}
        is SSAIntegerCoercion -> {}
        is SSANot -> {}
        is SSAThrow -> processThrow(insn)
    }

    private fun processCallSite(callSite: SSACallSite) {
        for (arg in callSite.operands) {
            ensureLocal(arg).escapeState = EscapeState.ESCAPE
        }
    }

    private fun processAlloc(insn: SSAAlloc) {
        val objectNode = CGObjectNode(insn.id, insn.type)
        objectNodes += objectNode

        val target = ensureLocal(insn)
        byPassReferenceNode(target)
        target.pointsTo(objectNode)
    }

    private fun processThrow(insn: SSAThrow) {
        if (insn.edge.to.id == SSABlockId.LandingPad) {

        }
    }

    private fun processReturn(insn: SSAReturn) {
        val local = ensureLocal(insn)
        local.escapeState = EscapeState.ESCAPE
    }

    private fun ensureLocal(value: SSAValue): CGReferenceVariable =
            referenceVariables.getOrPut(value) { CGReferenceVariable(value.id, value) }

    private fun byPassReferenceNode(node: CGReferenceNode) {
        referenceVariables.values.forEach { it.byPass(node) }
        objectNodes.flatMap { it.fieldNodes.values }.forEach { it.byPass(node) }
    }
}