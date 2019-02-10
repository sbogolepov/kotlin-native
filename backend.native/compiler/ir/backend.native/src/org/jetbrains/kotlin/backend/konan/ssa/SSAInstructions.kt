package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrCall

sealed class SSAInstruction(val owner: SSABlock, val operands: MutableList<SSAValue> = mutableListOf()): SSAValue {
    override val users = mutableSetOf<SSAInstruction>()

    init {
        operands.forEach { operand ->
            operand.users += this
        }
    }

    fun replaceBy(replacement: SSAValue) {
        replacement.users.addAll(users)
        for (user in users) {
            user.operands.replaceAll { op ->
                if (op == this) {
                    replacement
                } else {
                    op
                }
            }
        }
        operands.forEach { it.users -= this }
    }

    fun appendOperand(operand: SSAValue) {
        operands += operand
        operand.users += this
    }

    fun appendOperands(operands: List<SSAValue>) {
        operands.forEach { appendOperand(it) }
    }
}

class SSAIncRef(val ref: SSAValue, owner: SSABlock) : SSAInstruction(owner) {
    override val type = VoidType
}

class SSADecRef(val ref: SSAValue, owner: SSABlock) : SSAInstruction(owner) {
    override val type = VoidType
}

class SSANOP(val comment: String, owner: SSABlock) : SSAInstruction(owner) {
    override val type: SSAType = SpecialType
}

interface SSAReceiverAccessor {
    val receiver: SSAValue
}

sealed class SSACallSite(owner: SSABlock, operands: MutableList<SSAValue> = mutableListOf()) : SSAInstruction(owner, operands) {
    abstract val callee: SSAFunction
    abstract val irOrigin: IrCall

    override val type: SSAType
        get() = callee.type.returnType
}

class SSACall(
        override val callee: SSAFunction,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner)

class SSAMethodCall(
        override val receiver: SSAValue,
        override val callee: SSAFunction,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner, mutableListOf(receiver)), SSAReceiverAccessor

class SSAInvoke(
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner)

class SSAMethodInvoke(
        override val receiver: SSAValue,
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        owner: SSABlock,
        override val irOrigin: IrCall
): SSACallSite(owner, mutableListOf(receiver)), SSAReceiverAccessor

class SSABr(val edge: SSAEdge, owner: SSABlock) : SSAInstruction(owner) {
    override val type: SSAType = VoidType
}

class SSACondBr(
        condition: SSAValue,
        val truEdge: SSAEdge,
        val flsEdge: SSAEdge,
        owner: SSABlock
) : SSAInstruction(owner, mutableListOf(condition, truEdge, flsEdge)) {
    override val type: SSAType = VoidType

    val condition: SSAValue
        get() = operands[0]
}

class SSAReturn(retVal: SSAValue?, owner: SSABlock
) : SSAInstruction(owner, if (retVal != null) mutableListOf(retVal) else mutableListOf()) {
    override val type: SSAType = VoidType

    // replaceBy may invalidate retVal so we recompute it each time
    val retVal: SSAValue?
        get() = operands.getOrNull(0)
}

class SSAAlloc(override val type: SSAType, owner: SSABlock) : SSAInstruction(owner)

class SSAGetField(
        override val receiver: SSAValue,
        val field: SSAField,
        owner: SSABlock
) : SSAInstruction(owner), SSAReceiverAccessor {
    override val type: SSAType = field.type
}

class SSASetField(
        override val receiver: SSAValue,
        val field: SSAField,
        val value: SSAValue,
        owner: SSABlock
) : SSAInstruction(owner), SSAReceiverAccessor {
    override val type: SSAType = VoidType
}

class SSAGetObjectValue(override val type: SSAType, owner: SSABlock) : SSAInstruction(owner)

class SSACatch(owner: SSABlock) : SSAInstruction(owner) {
    override val type: SSAType = VoidType
}
