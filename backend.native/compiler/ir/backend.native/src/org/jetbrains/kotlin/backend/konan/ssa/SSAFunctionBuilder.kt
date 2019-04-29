package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrExpression

internal interface SSAFunctionBuilder {

    val function: SSAFunction

    var generationContext: GenerationContext<*>

    var curBlock: SSABlock

    val typeMapper: SSATypeMapper

    fun evalExpression(irExpr: IrExpression): SSAValue

    fun addBr(to: SSABlock): SSABr

    fun addCondBr(cond: SSAValue, tru: SSABlock, fls: SSABlock)

    fun createBlock(name: String): SSABlock

    fun createBlock(id: SSABlockId): SSABlock

    fun addBlock(ssaBlock: SSABlock)

    fun seal(block: SSABlock)

    fun SSABlock.addParam(type: SSAType): SSABlockParam

    fun <T: SSAInstruction> T.add(): T

    fun getUnit(): SSAValue

    fun getNothing(): SSAValue
}

internal inline fun SSAFunctionBuilder.appendingTo(block: SSABlock, code: () -> Unit) {
    val old = curBlock
    curBlock = block
    code()
    curBlock = old
}