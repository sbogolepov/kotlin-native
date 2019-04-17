package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.common.ir.isFinalClass
import org.jetbrains.kotlin.backend.konan.descriptors.isAbstract
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType

interface SSAType

object SpecialType : SSAType

object VoidType : SSAType

sealed class ReferenceType : SSAType

object SSAUnitType : ReferenceType()

object SSAAny : ReferenceType()

object SSAStringType : ReferenceType()

object SSANothingType: ReferenceType()

class SSAClass(
        val origin: IrClass,
        val superTypes: List<SSAClass>,
        val vtable: List<SSACallable>,
        val isFinal: Boolean,
        val isAbstact: Boolean
) : ReferenceType()

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