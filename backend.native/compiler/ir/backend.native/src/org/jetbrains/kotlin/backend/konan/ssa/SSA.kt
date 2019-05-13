package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.declarations.IrFunction

class SSAModule(val name: String, val index: SSAModuleIndex) {
    val functions = mutableListOf<SSAFunction>()
    val imports = mutableListOf<SSAFunction>()
    val classes = mutableListOf<SSAType>()
}

interface SSACallable {
    val name: String
    val type: SSAFuncType
    val irOrigin: IrFunction?
}

interface SSAValue {
    val type: SSAType
    val users: MutableSet<SSAInstruction>
}

// Hack: receiver for all global members
object SSAGlobalReceiver : SSAValue {
    override val users: MutableSet<SSAInstruction> = mutableSetOf()
    override val type: SSAType = SSASpecialType
}

fun SSACallSite.isMethod() = receiver != SSAGlobalReceiver

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

    object Undef : SSAConstant(SSASpecialType)
    object Unit : SSAConstant(SSAUnitType)
    object Null : SSAConstant(SSAAny)

    class Bool(val value: kotlin.Boolean) : SSAConstant(SSAPrimitiveType.BOOL)
    class Byte(val value: kotlin.Byte) : SSAConstant(SSAPrimitiveType.BYTE)
    class Char(val value: kotlin.Char) : SSAConstant(SSAPrimitiveType.CHAR)
    class Short(val value: kotlin.Short) : SSAConstant(SSAPrimitiveType.SHORT)
    class Int(val value: kotlin.Int) : SSAConstant(SSAPrimitiveType.INT)
    class Long(val value: kotlin.Long) : SSAConstant(SSAPrimitiveType.LONG)
    class Float(val value: kotlin.Float) : SSAConstant(SSAPrimitiveType.FLOAT)
    class Double(val value: kotlin.Double) : SSAConstant(SSAPrimitiveType.DOUBLE)
    class String(val value: kotlin.String) : SSAConstant(SSAStringType)
}

class SSAVirtualFunction(
        override val name: String,
        override val type: SSAFuncType,
        override val irOrigin: IrFunction? = null
) : SSACallable

class SSAFunction(
        override val name: String,
        override val type: SSAFuncType,
        override val irOrigin: IrFunction? = null
) : SSACallable {
    var dispatchReceiver: SSAReceiver? = null
    var extensionReceiver: SSAReceiver? = null
    val entry = SSABlock(this, SSABlockId.Entry)
    val blocks = mutableListOf(entry)
    val params = mutableListOf<SSAFuncArgument>()
    val metadata = mutableListOf<String>()
}

val SSAFunction.topSortedBlocks: List<SSABlock>
    get() {
        val workingList = mutableListOf(entry)
        val visited = mutableSetOf(entry)
        val result = mutableListOf<SSABlock>()
        while (workingList.isNotEmpty()) {
            val node = workingList[0]
            workingList.removeAt(0)
            result += node
            node.succs.forEach {
                if (it.to !in visited) {
                    visited += it.to
                    workingList += it.to
                }
            }
        }
        return result
    }

sealed class SSABlockId {
    object Entry : SSABlockId() {
        override fun toString(): String =
                "entry"
    }

    class Simple(val name: String = "") : SSABlockId() {
        override fun toString(): String =
                name
    }

    object LandingPad : SSABlockId() {
        override fun toString(): String =
                "landing_pad"
    }
}

// TODO: Add Exception edge
class SSAEdge(
        val from: SSABlock,
        var to: SSABlock,
        val args: MutableList<SSAValue> = mutableListOf()
) : SSAValue {

    init {
        from.succs += this
        to.preds += this
    }
    override val users: MutableSet<SSAInstruction> = mutableSetOf()

    override val type: SSAType = SSASpecialType
}

class SSABlock(val owner: SSAFunction, val id: SSABlockId = SSABlockId.Simple()) : SSAValue {

    override val users = mutableSetOf<SSAInstruction>()

    override val type: SSAType = SSABlockType()

    val params = mutableListOf<SSABlockParam>()
    val body: MutableList<SSAInstruction> = mutableListOf()
    val succs = mutableSetOf<SSAEdge>()
    val preds = mutableSetOf<SSAEdge>()
    var sealed: Boolean = false
}

fun SSABlockParam.getIncomingValues(): Set<SSAValue> {
    val index = owner.params.indexOf(this)
    return owner.succs.map { it.args[index] }.toSet()
}

fun SSAInstruction.isTerminal() = when (this) {
    is SSAReturn,
    is SSABr,
    is SSACondBr,
    is SSACatch,
    is SSAThrow,
    is SSAInvoke -> true
    else -> false
}