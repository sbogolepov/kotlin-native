package org.jetbrains.kotlin.backend.konan.ssa

interface SSAInstruction: SSAValue {
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
}

abstract class SSAInstructionBase(
        final override val operands: MutableList<SSAValue> = mutableListOf()
) : SSAInstruction {

    init {
        operands.forEach { it.users += this }
    }

    override val users = mutableSetOf<SSAInstruction>()

    fun appendOperand(operand: SSAValue) {
        operands += operand
        operand.users += this
    }
}

class SSAIncRef(val ref: SSAValue) : SSAInstructionBase() {
    override val type = VoidType
}

class SSADecRef(val ref: SSAValue) : SSAInstructionBase() {
    override val type = VoidType
}

class SSANOP(val comment: String) : SSAInstructionBase() {
    override val type: SSAType = SpecialType
}

interface SSAReceiverAccessor {
    val receiver: SSAValue
}

interface SSACallSite : SSAInstruction {
    val callee: SSAFunction

    override val type: SSAType
        get() = callee.type.returnType
}

class SSACall(
        override val callee: SSAFunction
) : SSAInstructionBase(), SSACallSite

class SSAMethodCall(
        override val receiver: SSAValue,
        override val callee: SSAFunction
) : SSAInstructionBase(mutableListOf(receiver)), SSACallSite, SSAReceiverAccessor

class SSAInvoke(
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge
) : SSAInstructionBase(), SSACallSite

class SSAMethodInvoke(
        override val receiver: SSAValue,
        override val callee: SSAFunction,
        val continuation: SSAEdge,
        val exception: SSAEdge
): SSAInstructionBase(mutableListOf(receiver)), SSACallSite, SSAReceiverAccessor

class SSABr(val edge: SSAEdge) : SSAInstructionBase(mutableListOf(edge)) {
    override val type: SSAType = VoidType
}

class SSACondBr(val condition: SSAValue, val truEdge: SSAEdge, val flsEdge: SSAEdge)
    : SSAInstructionBase(mutableListOf(condition, truEdge, flsEdge)) {
    override val type: SSAType = VoidType
}

class SSAReturn(val retVal: SSAValue): SSAInstructionBase(mutableListOf(retVal)) {
    override val type: SSAType = VoidType
}

class SSAAlloc(override val type: SSAType): SSAInstructionBase() {

}

class SSAGetField(
        override val receiver: SSAValue,
        val field: SSAField
): SSAInstructionBase(), SSAReceiverAccessor {
    override val type: SSAType = field.type
}

class SSASetField(
        override val receiver: SSAValue,
        val field: SSAField,
        val value: SSAValue
): SSAInstructionBase(), SSAReceiverAccessor {
    override val type: SSAType = VoidType
}

class SSAGetObjectValue(override val type: SSAType) : SSAInstructionBase()
