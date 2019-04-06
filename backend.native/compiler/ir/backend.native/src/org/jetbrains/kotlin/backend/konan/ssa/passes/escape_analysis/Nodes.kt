package org.jetbrains.kotlin.backend.konan.ssa.passes.escape_analysis

import org.jetbrains.kotlin.backend.konan.ssa.ReferenceType
import org.jetbrains.kotlin.backend.konan.ssa.SSAValue

class CGNodeId(private val id: String)

val SSAValue.id: CGNodeId
    get() = CGNodeId("${hashCode()}")

sealed class CGNode(val id: CGNodeId, var escapeState: EscapeState = EscapeState.UNKNOWN) {}

open class CGObjectNode(id: CGNodeId, val objectType: ReferenceType) : CGNode(id) {
    val fieldNodes = mutableMapOf<String, CGFieldNode>()
}

class CGPhantomObjectNode(id: CGNodeId) : CGNode(id)

sealed class CGReferenceNode(id: CGNodeId) : CGNode(id) {

    private val pointsToNodes = mutableMapOf<CGNodeId, CGObjectNode>()
    private val defferedNodes = mutableMapOf<CGNodeId, CGReferenceNode>()

    fun byPass(nodeToByPass: CGReferenceNode) {
        // TODO: check by id
        if (nodeToByPass.id == id) {
            return
        }
        if (nodeToByPass.id in defferedNodes) {
            defferedNodes -= nodeToByPass.id

            for (objectNode in nodeToByPass.pointsToNodes.values) {
                pointsTo(objectNode)
            }
            for (referenceNode in nodeToByPass.defferedNodes.values) {
                addNode(referenceNode)
            }
        }
    }

    fun addNode(referenceNode: CGReferenceNode) {
        if (referenceNode.id == id) {
            println("WARNING: node with id = $id references to itself.")
        } else {
            defferedNodes[referenceNode.id] = referenceNode
        }
    }

    fun pointsTo(objectNode: CGObjectNode) {
        pointsToNodes[objectNode.id] = objectNode
    }
}

class CGReferenceVariable(id: CGNodeId, val local: SSAValue) : CGReferenceNode(id)

sealed class CGFieldNode(id: CGNodeId) : CGReferenceNode(id) {
    class Static(id: CGNodeId) : CGFieldNode(id)

    class NonStatic(id: CGNodeId) : CGFieldNode(id)
}
