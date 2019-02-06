package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.js.translate.utils.splitToRanges

/**
 * To simplify translation to LLVM we can lower `SSACall` to `SSAInvoke`.
 * It will lead to 1 to 1 mapping of basic blocks.
 */
class CallsLoweringPass(val function: SSAFunction) : FunctionPass {

    val landingPad: SSABlock by lazy { SSABlock(function, SSABlockId.LandingPad) }

    override fun apply() {
        val wasLowered = function.blocks.fold(false) { acc, block -> acc or lowerBlock(function, block) }

        if (wasLowered) {
            function.blocks += landingPad
        }
    }

    private fun lowerBlock(function: SSAFunction, block: SSABlock): Boolean {
        for (insn in block.body) {
            if (insn is SSACallSite) {
                // Insertion in block.body will lead to concurrent modification exception
                insertInvoke(function, block, insn)
            }
        }
        return false
    }

    private fun insertInvoke(function: SSAFunction, block: SSABlock, callSite: SSACallSite) {
        val continuation = SSABlock(function, SSABlockId.Simple(-1)).apply {
            params += SSABlockParam(callSite.type, this)
        }
        val contEdge = SSAEdge(block, continuation)
        val excEdge = SSAEdge(block, landingPad)
        val newCallSite = when (callSite) {
            is SSAMethodCall -> SSAMethodInvoke(callSite.receiver, callSite.callee, contEdge, excEdge)
            is SSACall -> SSAInvoke(callSite.callee, contEdge, excEdge)
            else -> error("Unexpected call site type: $callSite")
        }
        contEdge.args += newCallSite

        callSite.replaceBy(newCallSite)

        val insnIdx = block.body.indexOf(callSite)
        val firstHalf = block.body.take(insnIdx)
        val secondHalf = block.body.drop(insnIdx + 1)

        block.body -= secondHalf
        block.body[block.body.size - 1] = newCallSite

        continuation.body.addAll(secondHalf)
        function.blocks.add(continuation)
    }
}