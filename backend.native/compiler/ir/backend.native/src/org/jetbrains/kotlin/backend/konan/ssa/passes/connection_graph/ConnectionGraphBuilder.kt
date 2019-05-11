package org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph

import org.jetbrains.kotlin.backend.konan.ssa.*

class GlobalConnectionGraphState {

    val slotToActualReference = mutableMapOf<SSAValue, CGReferenceNode.Actual>()

    val methodToReturn = mutableMapOf<SSAFunction, CGReferenceNode.Actual>()

    val invokeToReturn = mutableMapOf<SSACallSite, CGReferenceNode.Actual>()

    val invokeToActual = mutableMapOf<SSACallSite, List<CGReferenceNode.Actual>>()

    val methodToCGMap = mutableMapOf<SSAFunction, Map<SSAValue, CGNode>>()

    val methodToCalleeActuals = mutableMapOf<SSAFunction, List<CGReferenceNode.Actual>>()

    val methodToCallerActualsAndGlobals = mutableMapOf<SSAFunction, List<CGReferenceNode>>()

    val nodeToCg = mutableMapOf<SSAValue, CGNode>()
}

class ConnectionGraphBuilder(val function: SSAFunction) {

    val state = GlobalConnectionGraphState()

    val workingList = linkedSetOf(*function.blocks.toTypedArray())

    fun build() {
        val actuals = mutableListOf<CGReferenceNode.Actual>()
        if (function.type.returnType is ReferenceType) {
            actuals += CGReferenceNode.Actual.Return()
        }
        for (param in function.params) {
            if (param.type is ReferenceType) {
                CGReferenceNode.Actual.Parameter(param)
            }
        }
        val iter = workingList.iterator()
        while (iter.hasNext()) {
            val block = iter.next()
            workingList -= block
            processBlock(block)
        }
    }

    private fun processBlock(block: SSABlock) =
            block.body.forEach(::processInsn)

    private fun processInsn(insn: SSAInstruction): Unit = when (insn) {
        is SSADeclare -> TODO()
        is SSAIncRef -> TODO()
        is SSADecRef -> TODO()
        is SSANOP -> TODO()
        is SSAVirtualCall -> TODO()
        is SSAInterfaceCall -> TODO()
        is SSADirectCall -> TODO()
        is SSAInvoke -> TODO()
        is SSAGetITable -> TODO()
        is SSAGetVTable -> TODO()
        is SSABr -> TODO()
        is SSACondBr -> TODO()
        is SSAReturn -> TODO()
        is SSAAlloc -> handleAlloc(insn)
        is SSAGetField -> handleGetField(insn)
        is SSASetField -> TODO()
        is SSAGetGlobal -> TODO()
        is SSASetGlobal -> TODO()
        is SSAGetObjectValue -> TODO()
        is SSACatch -> TODO()
        is SSAInstanceOf -> TODO()
        is SSACast -> TODO()
        is SSAIntegerCoercion -> TODO()
        is SSANot -> TODO()
        is SSAThrow -> TODO()
    }

    private fun handleGetField(insn: SSAGetField) {
        if (insn.field.type !is ReferenceType) {
            return
        }
        val localReference = CGReferenceNode.getLocalReferenceNode(insn)
        state.nodeToCg[insn] = localReference

        val base = state.nodeToCg[insn.receiver] as? CGReferenceNode ?: error("Receiver is not processed.")

        val pointsTo = mutableSetOf(*base.pointsTo.toTypedArray())
        if (pointsTo.isEmpty()) {
            pointsTo += CGObjectNode.getPhantomNodeForReference(base)
        }
        pointsTo.forEach { objectNode ->
            objectNode.getFieldReferenceFor(insn.field).attachTo(localReference)
        }
    }

    private fun handleAlloc(insn: SSAAlloc) {
        state.nodeToCg[insn] = CGObjectNode.getObjectNodeForAllocation(insn, function)
    }
}
