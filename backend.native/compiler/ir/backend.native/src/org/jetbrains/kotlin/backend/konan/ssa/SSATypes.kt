package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType

interface SSAType {

}

object SpecialType : SSAType

object VoidType : SSAType

sealed class ReferenceType : SSAType

object NullRefType : ReferenceType()

object SSAStringType : ReferenceType()

class SSAClass(val irClass: IrClass) : ReferenceType()

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