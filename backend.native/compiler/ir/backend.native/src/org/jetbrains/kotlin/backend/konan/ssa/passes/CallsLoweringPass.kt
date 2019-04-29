package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import kotlin.contracts.contract

/**
 * To simplify translation to LLVM we can lower `SSADirectCall` to `SSAInvokeThrowing`.
 * It will lead to 1 to 1 mapping of basic blocks.
 */
class CallsLoweringPass : FunctionPass {

    override val name: String = "Call to invoke lowering"

    override fun apply(function: SSAFunction) {
        val landingPad: Lazy<SSABlock> = lazy {
            function.blocks.find { it.id == SSABlockId.LandingPad }
                    ?:
            SSABlock(function, SSABlockId.LandingPad).apply {
                body.add(SSACatch(this))
            }
        }

        val newBody = function.topSortedBlocks.fold(listOf<SSABlock>()) { body, block ->
            body + lowerBlock(function, block, landingPad)
        }

        function.blocks.clear()
        function.blocks += newBody
        if (landingPad.isInitialized()) {
            landingPad.value.sealed = true
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
                insn as SSACallSite
                val nextBlock = SSABlock(function)

                val contEdge = SSAEdge(curBlock, nextBlock)
                val excEdge = SSAEdge(curBlock, landingPad.value)

                val callee = when (insn) {
                    is SSAVirtualCall -> SSAGetVTable(insn.receiver, curBlock, insn.callee).also {
                        curBlock.body += it
                    }
                    is SSAInterfaceCall -> SSAGetITable(insn.receiver, curBlock, insn.callee).also {
                        curBlock.body += it
                    }
                    else -> insn.callee
                }
                val newCallSite = SSAInvoke(insn.receiver, insn.args, callee, contEdge, excEdge, curBlock, insn.irOrigin)
                insn.replaceBy(newCallSite)
                curBlock.body += newCallSite
                curBlock.sealed = true
                blocks += curBlock
                curBlock = nextBlock
            } else {
                when (insn) {
                    is SSABr -> SSABr(insn.edge.changeSrc(curBlock), curBlock)
                    is SSACondBr -> SSACondBr(
                            insn.condition,
                            insn.truEdge.changeSrc(curBlock),
                            insn.flsEdge.changeSrc(curBlock),
                            curBlock)
                    is SSAThrow -> SSAThrow(insn.edge.changeSrc(curBlock), curBlock)
                    else -> insn
                }.moveTo(curBlock)
            }
        }
        if (blocks.isEmpty() || blocks.last() != curBlock) {
            curBlock.sealed = true
            blocks += curBlock
        }
        return blocks
    }

    private fun shouldBeLowered(insn: SSAInstruction): Boolean {
        if (insn is SSACallSite) {
            val function = insn.irOrigin.symbol.owner
            return when {
                function.origin == IrDeclarationOrigin.IR_BUILTINS_STUB -> false
                function.isTypedIntrinsic -> false
                else -> true
            }
        }
        return false
    }
}