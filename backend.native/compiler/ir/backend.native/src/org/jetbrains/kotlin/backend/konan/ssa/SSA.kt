package org.jetbrains.kotlin.backend.konan.ssa

class SSAModule {
    val functions = mutableListOf<SSAFunction>()
    val imports = mutableListOf<SSAFunction>()
}

interface SSACallable

interface SSAValue {
    val users: MutableSet<SSAInstruction>
    val type: SSAType
}

class SSAFuncArgument(val name: String, override val type: SSAType) : SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
}

class SSABlockParam(override val type: SSAType, val owner: SSABlock) : SSAValue {
    override val users = mutableSetOf<SSAInstruction>()
}

class SSAGetObjectValue(override val type: SSAType) : SSAInstructionBase()

sealed class SSAConstant(override val type: SSAType) : SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    object Undef : SSAConstant(SpecialType)

    object Null : SSAConstant(ReferenceType())
    class Bool(val value: kotlin.Boolean): SSAConstant(SSAPrimitiveType.BOOL)
    class Byte(val value: kotlin.Byte) : SSAConstant(SSAPrimitiveType.BYTE)
    class Char(val value: kotlin.Char) : SSAConstant(SSAPrimitiveType.CHAR)
    class Short(val value: kotlin.Short): SSAConstant(SSAPrimitiveType.SHORT)
    class Int(val value: kotlin.Int) : SSAConstant(SSAPrimitiveType.INT)
    class Long(val value: kotlin.Long) : SSAConstant(SSAPrimitiveType.LONG)
    class Float(val value: kotlin.Float) : SSAConstant(SSAPrimitiveType.FLOAT)
    class Double(val value: kotlin.Double) : SSAConstant(SSAPrimitiveType.DOUBLE)
    class String(val value: kotlin.String): SSAConstant(ReferenceType())
}


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

class SSAPhi(val block: SSABlock): SSAInstructionBase() {
    override val type: SSAType = SpecialType
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

class SSAFunction(
        val name: String,
        val type: SSAFuncType
): SSACallable {
    val entry = SSABlock(this, SSABlockId.Entry)
    val blocks = mutableListOf(entry)
    val params = mutableListOf<SSAFuncArgument>()
}

sealed class SSABlockId {
    object Entry : SSABlockId() {
        override fun toString(): String =
                "entry"
    }

    class Simple(private val id: Int, val name: String = "") : SSABlockId() {
        override fun toString(): String =
            if (name.isEmpty()) "$id" else "${id}_$name"
    }
}

class SSAEdge(
        val from: SSABlock,
        val to: SSABlock,
        val args: MutableList<SSAValue> = mutableListOf()
): SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()

    override val type: SSAType = SpecialType
}

class SSABlock(val func: SSAFunction, val id: SSABlockId): SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    override val type: SSAType = SSABlockType()

    val params = mutableListOf<SSABlockParam>()
    val body: MutableList<SSAInstruction> = mutableListOf()
    val succs = mutableSetOf<SSAEdge>()
    val preds = mutableSetOf<SSAEdge>()
    var sealed: Boolean = false
}

fun SSAInstruction.isTerminal() = when(this) {
    is SSAReturn, is SSABr, is SSACondBr -> true
    else -> false
}