package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrType

class SSAModule {
    val functions = mutableListOf<SSAFunction>()
    val imports = mutableListOf<SSAFunction>()
}

interface SSACallable

interface SSAType {

}

class SSAWrapperType(val irType: IrType): SSAType

enum class SSAPrimitiveType: SSAType {
    BOOL,
    BYTE,
    CHAR,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE
}

class SSAFuncType(
        val returnType: SSAType,
        val parameterTypes: List<SSAType>
)

interface SSAValue {
    val users: MutableSet<SSAInstruction>
}

interface SSAUser {

}

class SSABlockArg : SSAValue, SSAUser {
    override val users = mutableSetOf<SSAInstruction>()
}

class SSAGetObjectValue() : SSAInstructionBase()

sealed class SSAConstant : SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    object Undef : SSAConstant()

    object Null : SSAConstant()
    class Bool(val value: kotlin.Boolean): SSAConstant()
    class Byte(val value: kotlin.Byte) : SSAConstant()
    class Char(val value: kotlin.Char) : SSAConstant()
    class Short(val value: kotlin.Short): SSAConstant()
    class Int(val value: kotlin.Int) : SSAConstant()
    class Long(val value: kotlin.Long) : SSAConstant()
    class Float(val value: kotlin.Float) : SSAConstant()
    class Double(val value: kotlin.Double) : SSAConstant()
    class String(val value: kotlin.String): SSAConstant()
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

}

class SSAPhi(val block: SSABlock): SSAInstructionBase() {

}

class SSABr(val target: SSABlock) : SSAInstructionBase(mutableListOf(target))

class SSACondBr(val condition: SSAValue, val truTarget: SSABlock, val flsTarget: SSABlock)
    : SSAInstructionBase(mutableListOf(condition, truTarget, flsTarget))

class SSAReturn(val retVal: SSAValue): SSAInstructionBase(mutableListOf(retVal))

class SSAFunction(
        val name: String,
        val type: SSAFuncType
): SSACallable {
    val entry = SSABlock(this, SSABlockId.Entry)
    val blocks = mutableListOf(entry)
}

sealed class SSABlockId {
    object Entry : SSABlockId() {
        override fun toString(): String =
                "entry"
    }

    class Simple(private val id: Int, val name: String = "") : SSABlockId() {
        override fun toString(): String =
            if (name.isEmpty()) {
                "$id"
            } else {
                "${id}_$name"
            }
    }
}

class SSAEdge(val from: SSABlock, val to: SSABlock, val value: SSAValue): SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
}

class SSABlock(val func: SSAFunction, val id: SSABlockId): SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    val args = mutableListOf<SSABlockArg>()
    val body: MutableList<SSAInstruction> = mutableListOf()
    val succs = mutableSetOf<SSABlock>()
    val preds = mutableSetOf<SSABlock>()
    var sealed: Boolean = false
}

fun SSAInstruction.isTerminal() = when(this) {
    is SSAReturn, is SSABr, is SSACondBr -> true
    else -> false
}