package org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph

import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.NestedMap

enum class EscapeState {
    Local, Method, Global
}

interface CGNode {
    var escapeState: EscapeState

    val descendants: List<CGNode>

    fun attachTo(reference: CGReferenceNode)
}

fun CGNode.updateEscapeState(newState: EscapeState) {
    when (newState) {
        EscapeState.Global -> {
            if (escapeState != EscapeState.Global) {
                escapeState = newState
                descendants.forEach { it.updateEscapeState(newState) }
            }
        }
        EscapeState.Method -> {
            if (escapeState == EscapeState.Global) {
                return
            }
            if (escapeState == EscapeState.Local) {
                escapeState = newState
                descendants.forEach { it.updateEscapeState(newState) }
            }
        }
        EscapeState.Local -> {
        }
    }
}

sealed class CGObjectNode : CGNode {

    companion object {
        private val allocationToObject = NestedMap<SSAAlloc, SSAFunction, Object>()

        private val referenceToPhantom = mutableMapOf<CGReferenceNode, Phantom>()

        private val fieldToFieldReference = mutableMapOf<SSAField, CGReferenceNode.Field>()

        fun getObjectNodeForAllocation(allocSite: SSAAlloc, function: SSAFunction): Object =
                allocationToObject.getOrPut(allocSite, function) { Object(allocSite) }

        fun getPhantomNodeForReference(reference: CGReferenceNode): List<Phantom> =
                if (reference.defferedEdges.isEmpty()) {
                    listOf(referenceToPhantom.getOrPut(reference) { Phantom(reference) })
                } else {
                    reference.defferedEdges.flatMap { getPhantomNodeForReference(it) }
                }
    }

    val fields: MutableList<CGReferenceNode.Field> = mutableListOf()

    override var escapeState: EscapeState = EscapeState.Local

    override val descendants: List<CGNode>
        get() = fields

    override fun attachTo(reference: CGReferenceNode) {
        if (reference.immediatePointsTo.add(this)) {
            updateEscapeState(reference.escapeState)
        }
    }

    private fun addField(fieldNode: CGReferenceNode.Field) {
        fields += fieldNode
    }

    fun getFieldReferenceFor(field: SSAField): CGReferenceNode.Field =
            fieldToFieldReference.getOrPut(field) { CGReferenceNode.Field(this, field) }.also { addField(it) }

    class Constant : CGObjectNode()

    class Phantom(val reference: CGReferenceNode) : CGObjectNode() {
        init {

        }
    }

    class Object(val allocSite: SSAAlloc) : CGObjectNode()
}

sealed class CGReferenceNode : CGNode {

    companion object {
        private val nodeToLocalReference = mutableMapOf<SSAValue, Local>()
        private val referenceNodeList = mutableListOf<CGReferenceNode>()
        private val fieldToGlobalReference = mutableMapOf<SSAField, Global>()
        private val invocationToActualReference = mutableMapOf<SSACallSite, MutableList<Actual>>()

        fun getLocalReferenceNode(value: SSAValue): Local =
                nodeToLocalReference.getOrPut(value) { Local(value) }.also {
                    referenceNodeList += it
                }

        fun getGlobalReferenceNode(field: SSAField, callerActualsAndGlobals: MutableList<CGReferenceNode>): Global {
            val global = fieldToGlobalReference.getOrPut(field) { Global(field) }
            callerActualsAndGlobals += global
            global.updateEscapeState(EscapeState.Global)
            return global
        }

        fun getArgumentActualReferences(ssaCallSite: SSACallSite, nodeToCg: MutableMap<SSAValue, CGNode>, callerActualsAndGlobals: MutableList<CGReferenceNode>): List<Actual> =
            invocationToActualReference.getOrPut(ssaCallSite) {
                val resultLength = ssaCallSite.args.size + 1

                val actualArguments = mutableListOf<Actual>()

                actualArguments
            }
    }

    override var escapeState: EscapeState = EscapeState.Local

    val immediatePointsTo: MutableSet<CGObjectNode> = mutableSetOf()

    val defferedEdges: MutableSet<CGReferenceNode> = mutableSetOf()

    val pointsTo: Set<CGObjectNode>
        get() = calculatePointsTo(mutableSetOf(), mutableSetOf())

    override val descendants: List<CGNode>
        get() = (immediatePointsTo + defferedEdges).toList()

    private fun calculatePointsTo(pointees: MutableSet<CGObjectNode>, visited: MutableSet<CGReferenceNode>): Set<CGObjectNode> {
        pointees += immediatePointsTo
        visited += this

        for (it in defferedEdges) {
            if (it in visited) continue
            visited += it
            it.calculatePointsTo(pointees, visited)
        }
        return pointees
    }

    override fun attachTo(reference: CGReferenceNode) {
        if (reference.defferedEdges.add(this)) {
            updateEscapeState(reference.escapeState)
        }
    }

    class Local(val value: SSAValue) : CGReferenceNode()

    class Field(obj: CGObjectNode, val field: SSAField) : CGReferenceNode() {
        init {
            updateEscapeState(obj.escapeState)
            referenceNodeList += this
        }
    }

    class Global(val field: SSAField) : CGReferenceNode()

    sealed class Actual : CGReferenceNode() {
        class Return : Actual() {
            init {
                updateEscapeState(EscapeState.Method)
            }
        }

        class Parameter(val param: SSAFuncArgument) : Actual() {
            init {
                updateEscapeState(EscapeState.Method)
            }
        }
    }
}