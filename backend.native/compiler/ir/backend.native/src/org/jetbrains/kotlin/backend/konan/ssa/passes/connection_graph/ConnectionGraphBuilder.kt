package org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph

import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.FunctionPass

class ConnectionGraphBuilderPass() : FunctionPass {
    override val name: String = "Connection graph building phase"

    override fun apply(function: SSAFunction) {
        if (!function.name.startsWith("checkMe")) {
            return
        }

        val connectionGraph = ConnectionGraphBuilder(function)
        connectionGraph.build()
        val allocToEscapeState = connectionGraph.state.nodeToCg

        println("### Escape Analysis results for ${function.name}")
        val metaInfoFn: (SSAInstruction) -> String? = {
            val node = allocToEscapeState[it]
            if (node == null) {
                null
            } else {
                "$node -> ${node.escapeState}"
            }
        }
        println(SSARender(metaInfoFn).render(function))
        println()
    }
}

class GlobalConnectionGraphState {

    val slotToActualReference = mutableMapOf<SSAValue, CGReferenceNode.Actual>()

    val methodToReturn = mutableMapOf<SSAFunction, CGReferenceNode.Actual>()

    val invokeToReturn = mutableMapOf<SSACallSite, CGReferenceNode.Actual>()

    val invokeToActual = mutableMapOf<SSACallSite, List<CGReferenceNode.Actual>>()

    val methodToCGMap = mutableMapOf<SSAFunction, Map<SSAValue, CGNode>>()

    val methodToCalleeActuals = mutableMapOf<SSAFunction, List<CGReferenceNode.Actual>>()

    val methodToCallerActualsAndGlobals = mutableMapOf<SSAFunction, List<CGReferenceNode>>()

    val nodeToCg = mutableMapOf<SSAValue, CGNode>()

    val callerActualsAndGlobals = mutableListOf<CGReferenceNode>()
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
                CGReferenceNode.Actual.FormalParameter(param)
            }
        }

        while (workingList.isNotEmpty()) {
            val iter = workingList.iterator()
            val block = iter.next()
            iter.remove()
            processBlock(block)
        }
    }

    private fun processBlock(block: SSABlock) {
        block.params.filter { it.type is ReferenceType }.forEach { param ->
            val localRef = CGReferenceNode.getLocalReferenceNode(param)
            state.nodeToCg[param] = localRef
            // TODO: Not correct since incoming values may come from unprocessed blocks.
            //  We should be conservative about unknown incoming value and set it to global escape.
            //  Then revisit this part again.
            val (escapeState, converged) = mergeIncomingValues(param.getIncomingValues())
            localRef.updateEscapeState(escapeState)
            if (!converged) {
                // TODO: Add block
            }
        }
        block.body.forEach(::processInsn)
    }

    private data class MergeResult<T>(val data: T, val converged: Boolean)

    private fun mergeIncomingValues(values: Set<SSAValue>): MergeResult<EscapeState> {
        var converged = true
        val max = values.map {
            state.nodeToCg[it]?.escapeState
                    ?: EscapeState.Global.also { converged = false }
        }.max() ?: EscapeState.Global
        return MergeResult(max, converged)
    }

    private fun processInsn(insn: SSAInstruction) {
        handleOperands(insn)
        return when (insn) {
            is SSADeclare -> TODO()
            is SSAIncRef -> {
            }
            is SSADecRef -> {
            }
            is SSANOP -> {
            }
            is SSAVirtualCall -> handleCallSite(insn)
            is SSAInterfaceCall -> handleCallSite(insn)
            is SSADirectCall -> handleCallSite(insn)
            is SSAInvoke -> handleCallSite(insn)
            is SSAGetITable -> {
            }
            is SSAGetVTable -> {
            }
            is SSABr -> {
            }
            is SSACondBr -> {
            }
            is SSAReturn -> handleReturn(insn)
            is SSAAlloc -> handleAlloc(insn)
            is SSAGetField -> handleGetField(insn)
            is SSASetField -> handleSetField(insn)
            is SSAGetGlobal -> handleGetGlobal(insn)
            is SSASetGlobal -> handleSetGlobal(insn)
            is SSAGetObjectValue -> {
            }
            is SSACatch -> {
            }
            is SSAInstanceOf -> {
            }
            is SSACast -> handleCast(insn)
            is SSAIntegerCoercion -> {
            }
            is SSANot -> {
            }
            is SSAThrow -> handleThrow(insn)
        }
    }

    private fun handleOperands(insn: SSAInstruction) {
        insn.operands.filterIsInstance<SSAConstant>().filter { it.type is ReferenceType }.forEach {
            state.nodeToCg[it] = CGObjectNode.getObjectNodeForConstant(it)
        }
    }

    private fun handleReturn(ret: SSAReturn) {

    }

    private fun handleThrow(throwInsn: SSAThrow) {
        throwInsn.edge.args.filter { it.type is ReferenceType }.forEach {
            state.nodeToCg[it]?.updateEscapeState(EscapeState.Global)
                    ?: error("zzz")
        }
    }

    private fun handleCast(cast: SSACast) {
        state.nodeToCg[cast] = state.nodeToCg[cast.value] ?: error("zzz")
    }

    private fun handleCallSite(insn: SSACallSite) {
        val actualArguments = CGReferenceNode.getArgumentActualReferences(insn, state.nodeToCg, state.callerActualsAndGlobals)
        if (insn.callee.type.returnType !is ReferenceType) {
            return
        }
        actualArguments.first?.let { state.nodeToCg[insn] = it }
    }

    private fun handleGetField(insn: SSAGetField) {
        if (insn.field.type !is ReferenceType) {
            return
        }
        val localReference = CGReferenceNode.getLocalReferenceNode(insn)
        state.nodeToCg[insn] = localReference

        val base = state.nodeToCg[insn.receiver] as? CGReferenceNode
                ?: error("Receiver is not processed.")

        val pointsTo = base.pointsTo.toMutableSet()
        if (pointsTo.isEmpty()) {
            pointsTo += CGObjectNode.getPhantomNodeForReference(base)
        }
        pointsTo.forEach { objectNode ->
            objectNode.getFieldReferenceFor(insn.field).attachTo(localReference)
        }
    }

    private fun handleSetField(setField: SSASetField) {
        val field = setField.field
        if (field.type !is ReferenceType) {
            return
        }
        val base = state.nodeToCg[setField.receiver] as? CGReferenceNode
                ?: error("Receiver is not processed.")
        val valueNode = state.nodeToCg[setField.value]
                ?: error("Field value is not processed.")

        val pointsTo = base.pointsTo.toMutableSet()
        if (pointsTo.isEmpty()) {
            pointsTo += CGObjectNode.getPhantomNodeForReference(base)
        }
        pointsTo.forEach { objectNode ->
            valueNode.attachTo(objectNode.getFieldReferenceFor(field))
        }
    }

    private fun handleGetGlobal(getGlobal: SSAGetGlobal) {
        if (getGlobal.global.type !is ReferenceType) {
            return
        }
        state.nodeToCg[getGlobal] = CGReferenceNode.getGlobalReferenceNode(getGlobal.global, state.callerActualsAndGlobals)
    }

    private fun handleSetGlobal(setGlobal: SSASetGlobal) {
        if (setGlobal.global.type !is ReferenceType) {
            return
        }
        val global = CGReferenceNode.getGlobalReferenceNode(setGlobal.global, state.callerActualsAndGlobals)
        val valueNode = state.nodeToCg[setGlobal.value]
                ?: error("Global value is not processed.")

        valueNode.attachTo(global)
    }

    private fun handleAlloc(insn: SSAAlloc) {
        state.nodeToCg[insn] = CGObjectNode.getObjectNodeForAllocation(insn, function)
    }
}
