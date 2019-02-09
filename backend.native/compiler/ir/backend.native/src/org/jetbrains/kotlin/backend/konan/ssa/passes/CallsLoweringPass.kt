package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.ssa.*

/**
 * To simplify translation to LLVM we can lower `SSACall` to `SSAInvoke`.
 * It will lead to 1 to 1 mapping of basic blocks.
 */
class CallsLoweringPass : FunctionPass {

    override fun apply(function: SSAFunction) {
        val landingPad: Lazy<SSABlock> = lazy { SSABlock(function, SSABlockId.LandingPad) }

        val newBody = function.blocks.fold(listOf<SSABlock>()) { body, block ->
            body + lowerBlock(function, block, landingPad)
        }

        function.blocks.clear()
        function.blocks += newBody
        if (landingPad.isInitialized()) {
            function.blocks += landingPad.value
        }
    }

    private fun lowerBlock(function: SSAFunction, block: SSABlock, landingPad: Lazy<SSABlock>): List<SSABlock> {

        val blocks = mutableListOf<SSABlock>()

        var curBlock = SSABlock(function, block.id).apply {
            params.addAll(block.params)
        }
        block.replaceWith(curBlock)

        for (insn in block.body) {
            if (shouldBeLowered(insn)) {
                val nextBlock = SSABlock(function)

                val contEdge = SSAEdge(curBlock, nextBlock)
                val excEdge = SSAEdge(curBlock, landingPad.value)

                val newCallSite = when (insn) {
                    is SSAMethodCall -> SSAMethodInvoke(insn.receiver, insn.callee, contEdge, excEdge, curBlock, insn.irOrigin).apply {
                        appendOperands(insn.operands.drop(1))
                    }
                    is SSACall -> SSAInvoke(insn.callee, contEdge, excEdge, curBlock, insn.irOrigin).apply {
                        appendOperands(insn.operands)
                    }
                    else -> error("Unexpected call site type: $insn")
                }
                insn.replaceBy(newCallSite)
                curBlock.body += newCallSite

                blocks += curBlock
                curBlock = nextBlock
            } else {
                curBlock.body += when (insn) {
                    is SSABr -> SSABr(insn.edge.changeSrc(curBlock), curBlock)
                    is SSACondBr -> SSACondBr(
                            insn.condition,
                            insn.truEdge.changeSrc(curBlock),
                            insn.flsEdge.changeSrc(curBlock),
                            curBlock)
                    else -> insn
                }
            }
        }
        if (blocks.isEmpty() || blocks.last() != curBlock) {
            blocks += curBlock
        }
        return blocks
    }

    private fun shouldBeLowered(insn: SSAInstruction): Boolean {
        if (insn is SSACallSite) {
            return !insn.irOrigin.symbol.owner.isTypedIntrinsic
        }
        return false
    }
}