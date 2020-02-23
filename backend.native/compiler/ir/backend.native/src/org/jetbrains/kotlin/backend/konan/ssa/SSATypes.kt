package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType

interface SSAType

object SSASpecialType : SSAType

object VoidType : SSAType

sealed class ReferenceType : SSAType

object SSAUnitType : ReferenceType()

object SSAAny : ReferenceType()

object SSAStringType : ReferenceType()

object SSANothingType: ReferenceType()

class SSAClass : ReferenceType() {
    lateinit var origin: IrClass
    var superTypes = mutableListOf<SSAClass>()
    var vtable = mutableListOf<SSAFunction>()
    var itable =  mutableListOf<SSAFunction>()
    var isFinal: Boolean = false
    var isAbstact: Boolean = false
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
) : SSAType {
    val isVararg = false
}

class SSABlockType : SSAType