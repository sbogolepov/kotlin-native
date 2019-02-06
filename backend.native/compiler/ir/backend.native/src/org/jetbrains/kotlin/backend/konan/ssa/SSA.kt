package org.jetbrains.kotlin.backend.konan.ssa

class SSAModule(val name: String, val index: SSAModuleIndex) {
    val functions = mutableListOf<SSAFunction>()
    val imports = mutableListOf<SSAFunction>()
}

interface SSACallable

interface SSAValue {
    val users: MutableSet<SSAInstruction>
    val type: SSAType
}

// TODO: Add receiver?
class SSAField(val name: String, override val type: SSAType) : SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
}

class SSAReceiver(override val type: SSAType) : SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
}

class SSAFuncArgument(val name: String, override val type: SSAType) : SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
}

class SSABlockParam(override val type: SSAType, val owner: SSABlock) : SSAValue {
    override val users = mutableSetOf<SSAInstruction>()
}

sealed class SSAConstant(override val type: SSAType) : SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    object Undef : SSAConstant(SpecialType)

    object Null : SSAConstant(NullRefType)
    class Bool(val value: kotlin.Boolean): SSAConstant(SSAPrimitiveType.BOOL)
    class Byte(val value: kotlin.Byte) : SSAConstant(SSAPrimitiveType.BYTE)
    class Char(val value: kotlin.Char) : SSAConstant(SSAPrimitiveType.CHAR)
    class Short(val value: kotlin.Short): SSAConstant(SSAPrimitiveType.SHORT)
    class Int(val value: kotlin.Int) : SSAConstant(SSAPrimitiveType.INT)
    class Long(val value: kotlin.Long) : SSAConstant(SSAPrimitiveType.LONG)
    class Float(val value: kotlin.Float) : SSAConstant(SSAPrimitiveType.FLOAT)
    class Double(val value: kotlin.Double) : SSAConstant(SSAPrimitiveType.DOUBLE)
    class String(val value: kotlin.String): SSAConstant(SSAStringType)
}

class SSAFunction(
        val name: String,
        val type: SSAFuncType
): SSACallable {
    var receiver: SSAReceiver? = null
    val entry = SSABlock(this, SSABlockId.Entry)
    val blocks = mutableListOf(entry)
    val params = mutableListOf<SSAFuncArgument>()
    val metadata = mutableListOf<String>()
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

    object LandingPad : SSABlockId() {
        override fun toString(): String =
                "landing_pad"
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