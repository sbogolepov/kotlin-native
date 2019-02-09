package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrCall


interface SSAInstruction: SSAValue {
    val owner: SSABlock
    val operands: MutableList<SSAValue>

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

    fun detach() {
        owner.body -= this
    }
}

abstract class SSAInstructionBase(
        final override val operands: MutableList<SSAValue> = mutableListOf()
) : SSAInstruction {

    init {
        operands.forEach { operand ->
            operand.users += this
        }
    }

    override val users = mutableSetOf<SSAInstruction>()

    fun appendOperand(operand: SSAValue) {
        operands += operand
        operand.users += this
    }

    fun appendOperands(operands: List<SSAValue>) {
        operands.forEach { appendOperand(it) }
    }
}

class SSAIncRef(val ref: SSAValue, override val owner: SSABlock) : SSAInstructionBase() {
    override val type = VoidType
}

class SSADecRef(val ref: SSAValue, override val owner: SSABlock) : SSAInstructionBase() {
    override val type = VoidType
}

class SSANOP(val comment: String, override val owner: SSABlock) : SSAInstructionBase() {
    override val type: SSAType = SpecialType
}

interface SSAReceiverAccessor {
    val receiver: SSAValue
}

sealed class SSACallSite(operands: MutableList<SSAValue> = mutableListOf()) : SSAInstructionBase(operands) {
    abstract val callee: SSAFunction
    abstract val irOrigin: IrCall

    override val type: SSAType
        get() = callee.type.returnType
}

class SSACall(
        override val callee: SSAFunction,
        override val owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite()

class SSAMethodCall(
        override val receiver: SSAValue,
        override val callee: SSAFunction,
        override val owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(mutableListOf(receiver)), SSAReceiverAccessor

class SSAInvoke(
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        override val owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite()

class SSAMethodInvoke(
        override val receiver: SSAValue,
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        override val owner: SSABlock,
        override val irOrigin: IrCall
): SSACallSite(mutableListOf(receiver)), SSAReceiverAccessor

class SSABr(val edge: SSAEdge, override val owner: SSABlock) : SSAInstructionBase(mutableListOf(edge)) {
    override val type: SSAType = VoidType
}

class SSACondBr(
        condition: SSAValue,
        val truEdge: SSAEdge,
        val flsEdge: SSAEdge,
        override val owner: SSABlock
) : SSAInstructionBase(mutableListOf(condition, truEdge, flsEdge)) {
    override val type: SSAType = VoidType

    val condition: SSAValue
        get() = operands[0]
}

class SSAReturn(retVal: SSAValue?, override val owner: SSABlock
): SSAInstructionBase(if (retVal != null) mutableListOf(retVal) else mutableListOf()) {
    override val type: SSAType = VoidType

    // replaceBy may invalidate retVal so we recompute it each time
    val retVal: SSAValue?
        get() = operands.getOrNull(0)
}

class SSAAlloc(override val type: SSAType, override val owner: SSABlock): SSAInstructionBase()

class SSAGetField(
        override val receiver: SSAValue,
        val field: SSAField,
        override val owner: SSABlock
): SSAInstructionBase(), SSAReceiverAccessor {
    override val type: SSAType = field.type
}

class SSASetField(
        override val receiver: SSAValue,
        val field: SSAField,
        val value: SSAValue,
        override val owner: SSABlock
): SSAInstructionBase(), SSAReceiverAccessor {
    override val type: SSAType = VoidType
}

class SSAGetObjectValue(override val type: SSAType, override val owner: SSABlock) : SSAInstructionBase()
