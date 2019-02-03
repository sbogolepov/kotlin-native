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
        this.operands.forEach { it.users -= this }
    }

}

abstract class SSAInstructionBase(
        override val operands: MutableList<SSAValue> = mutableListOf()
) : SSAInstruction {

    override val users = mutableSetOf<SSAInstruction>()

    fun appendOperand(operand: SSAValue) {
        operands += operand
        operand.users += this
    }
}

class SSANOP(val comment: String) : SSAInstructionBase() {
    override val type: SSAType = SpecialType
}

class SSACall(val callee: SSAFunction) : SSAInstructionBase() {
    override val type: SSAType = callee.type.returnType
}

class SSAMethodCall(val receiver: SSAValue, val callee: SSAFunction): SSAInstructionBase(mutableListOf(receiver)) {
    override val type: SSAType = callee.type.returnType
}

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

class SSAGetField(val receiver: SSAValue, val field: SSAField): SSAInstructionBase() {
    override val type: SSAType = field.type
}

class SSASetField(val receiver: SSAValue, val field: SSAField, val value: SSAValue): SSAInstructionBase() {
    override val type: SSAType = VoidType
}

class SSAGetObjectValue(override val type: SSAType) : SSAInstructionBase()
