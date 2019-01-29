package org.jetbrains.kotlin.backend.konan.ssa

interface SSAInstruction: SSAValue {
    val operands: MutableList<SSAValue>

    fun replaceBy(replace: SSAValue) {
        replace.users.addAll(users)
        for (user in users) {
            user.operands.replaceAll { op ->
                if (op === this) {
                    replace
                } else {
                    op
                }
            }
        }
    }

}

abstract class SSAInstructionBase(
        override val operands: MutableList<SSAValue> = mutableListOf()
) : SSAInstruction {

    override val users = mutableSetOf<SSAInstruction>()

    fun appendOperand(operand: SSAValue) {
        operands += operand
    }
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