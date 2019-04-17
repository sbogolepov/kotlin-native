package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrCall

// Instruction interface rules:
//  1. Named operands should be expressed as properties pointing at element at operands list
//     since it can be changed
sealed class SSAInstruction(owner: SSABlock, val operands: MutableList<SSAValue> = mutableListOf()): SSAValue {

    var comment: String? = null

    override val users = mutableSetOf<SSAInstruction>()

    private var _owner: SSABlock = owner

    val owner: SSABlock
        get() = _owner

    init {
        operands.forEach { operand ->
            operand.users += this
        }
    }

    // doesn't remove from previous block
    fun moveTo(newOwner: SSABlock) {
//        _owner.body -= this
        _owner = newOwner
        _owner.body += this
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
}

class SSADeclare(val name: String, value: SSAValue, owner: SSABlock) : SSAInstruction(owner, mutableListOf(value)) {
    override val type: SSAType = value.type

    val value: SSAValue
        get() = operands[0]
}

class SSAIncRef(val ref: SSAValue, owner: SSABlock) : SSAInstruction(owner) {
    override val type = VoidType
}

class SSADecRef(val ref: SSAValue, owner: SSABlock) : SSAInstruction(owner) {
    override val type = VoidType
}

class SSANOP(comment: String, owner: SSABlock) : SSAInstruction(owner) {
    init {
        this.comment = comment
    }
    override val type: SSAType = SpecialType
}

interface SSAReceiverAccessor {
    val receiver: SSAValue
}

sealed class SSACallSite(owner: SSABlock, operands: MutableList<SSAValue> = mutableListOf()) : SSAInstruction(owner, operands) {
    abstract val callee: SSACallable
    abstract val irOrigin: IrCall

    override val type: SSAType
        get() = callee.type.returnType

    abstract val args: List<SSAValue>
}

class SSACall(
        args: List<SSAValue>,
        override val callee: SSACallable,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner, args.toMutableList()) {

    override val args: List<SSAValue>
        get() = operands
}

class SSAMethodCall(
        receiver: SSAValue,
        args: List<SSAValue>,
        override val callee: SSACallable,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner, mutableListOf(receiver, *args.toTypedArray())), SSAReceiverAccessor {

    override val receiver: SSAValue
        get() = operands[0]

    override val args: List<SSAValue>
        get() = operands.drop(1)
}

class SSAInvoke(
        args: List<SSAValue>,
        override val callee: SSACallable,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        owner: SSABlock,
        override val irOrigin: IrCall
) : SSACallSite(owner, args.toMutableList()) {
    override val args: List<SSAValue>
        get() = operands
}

class SSAMethodInvoke(
        receiver: SSAValue,
        args: List<SSAValue>,
        override val callee: SSACallable,
        val continuation: SSAEdge,
        val exception: SSAEdge,
        owner: SSABlock,
        override val irOrigin: IrCall
): SSACallSite(owner, mutableListOf(receiver, *args.toTypedArray())), SSAReceiverAccessor {
    override val receiver: SSAValue
        get() = operands[0]

    override val args: List<SSAValue>
        get() = operands.drop(1)
}

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

class SSAAlloc(override val type: ReferenceType, owner: SSABlock) : SSAInstruction(owner)

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

class SSAGetGlobal(
        val global: SSAField,
        owner: SSABlock
) : SSAInstruction(owner) {
    override val type: SSAType = global.type
}

class SSASetGlobal(
        val global: SSAField,
        val value: SSAValue,
        owner: SSABlock
) : SSAInstruction(owner) {
    override val type: SSAType = VoidType
}

class SSAGetObjectValue(override val type: SSAType, owner: SSABlock) : SSAInstruction(owner)

class SSACatch(owner: SSABlock) : SSAInstruction(owner) {
    override val type: SSAType = VoidType
}

// TODO: how to encode type?
class SSAInstanceOf(value: SSAValue, val typeOperand: SSAType, owner: SSABlock) :
        SSAInstruction(owner, mutableListOf(value)) {
    override val type: SSAType = SSAPrimitiveType.BOOL

    val value: SSAValue
        get() = operands[0]
}

class SSACast(value: SSAValue, val typeOperand: SSAType, owner: SSABlock) :
        SSAInstruction(owner, mutableListOf(value)) {
    override val type: SSAType = typeOperand

    val value: SSAValue
        get() = operands[0]
}

class SSAIntegerCoercion(value: SSAValue, val typeOperand: SSAType, owner: SSABlock) :
        SSAInstruction(owner, mutableListOf(value)) {
    override val type: SSAType = typeOperand

    val value: SSAValue
        get() = operands[0]
}

class SSANot(value: SSAValue, owner: SSABlock):
        SSAInstruction(owner, mutableListOf(value)) {
    override val type: SSAType = SSAPrimitiveType.BOOL

    val value: SSAValue
        get() = operands[0]
}

class SSAThrow(exception: SSAEdge, owner: SSABlock):
        SSAInstruction(owner, mutableListOf(exception)) {
    override val type: SSAType = exception.type

    val edge: SSAEdge
        get() = operands[0] as SSAEdge
}
