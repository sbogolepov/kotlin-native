package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

interface StateManager<S> {

    fun init(): S

    fun merge(s1: S, s2: S): S

    fun basicBlockStart(block: SSABlock)

    fun visitInsn(insn: SSAInstruction)

    fun basicBlockEnd(block: SSABlock)
}

class SSAAbstractInterpreter<S>(
        val function: SSAFunction,
        val stateManager: StateManager<S>
) {
    var converged = true

    private val blockToState = mutableMapOf<SSABlock, S>()

    private val workList = WorkList(listOf(function.entry))

    fun processBlock(block: SSABlock) {
        for (incoming in block.preds) {
            if (incoming.from !in blockToState) {
                converged = false
            }
        }
        stateManager.basicBlockStart(block)
        block.body.forEach { stateManager.visitInsn(it) }
        stateManager.basicBlockEnd(block)
        block.body.lastOrNull().let { last ->
            when (last) {
                is SSAReturn -> {

                }
                is SSABr -> tryAddBlockToWorklist(last.edge)
                is SSACondBr -> {
                    tryAddBlockToWorklist(last.truEdge)
                    tryAddBlockToWorklist(last.flsEdge)
                }
                is SSACatch -> {

                }
                is SSAThrow -> {
                    tryAddBlockToWorklist(last.edge)
                }
                is SSAInvoke -> {
                    tryAddBlockToWorklist(last.exception)
                    tryAddBlockToWorklist(last.continuation)
                }

            }
        }
    }

    private fun tryAddBlockToWorklist(edge: SSAEdge) {

        workList.add(edge.to)
    }

    fun interpret() {
        stateManager.init()

    }
}