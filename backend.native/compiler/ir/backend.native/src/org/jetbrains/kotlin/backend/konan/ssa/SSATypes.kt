package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.types.IrType

interface SSAType {

}

object SpecialType : SSAType

object VoidType : SSAType

open class ReferenceType : SSAType

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
) : SSAType

class SSABlockType : SSAType