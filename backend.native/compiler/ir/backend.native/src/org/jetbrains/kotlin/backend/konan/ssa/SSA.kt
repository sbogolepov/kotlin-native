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

abstract class SSAInstructionBase : SSAInstruction {
    override val operands: MutableList<SSAValue> = mutableListOf()

    override val users = mutableSetOf<SSAInstruction>()

    fun appendOperand(operand: SSAValue) {
        operands += operand
    }
}

class SSACall(val callee: SSAFunction) : SSAInstructionBase() {

}

class SSAPhi(val block: SSABlock): SSAInstructionBase() {


}

class SSAReturn(): SSAInstructionBase()

class SSAFunction(
        val name: String,
        val type: SSAFuncType
): SSACallable {
    val entry = SSABlock(this, SSABlockId(0))
}

class SSABlockId(private val id: Int) {
    override fun toString(): String {
        return id.toString()
    }
}

class SSABlock(val func: SSAFunction, val id: SSABlockId) {
    val args = mutableListOf<SSABlockArg>()
    val body: MutableList<SSAInstruction> = mutableListOf()
    val succs: MutableList<SSABlock> = mutableListOf()
    val preds: MutableList<SSABlock> = mutableListOf()
    var sealed: Boolean = false
}