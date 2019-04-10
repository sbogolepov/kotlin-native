package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.ir.allParameters
import org.jetbrains.kotlin.backend.konan.ir.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.ir.isOverridable
import org.jetbrains.kotlin.backend.konan.ir.name
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class SSATypeMapper {
    private val typeCache = mutableMapOf<IrType, SSAType>()

    fun map(irType: IrType): SSAType = typeCache.getOrPut(irType) {
        when {
            irType.isBoolean() -> SSAPrimitiveType.BOOL
            irType.isByte() -> SSAPrimitiveType.BYTE
            irType.isChar() -> SSAPrimitiveType.CHAR
            irType.isShort() -> SSAPrimitiveType.SHORT
            irType.isInt() -> SSAPrimitiveType.INT
            irType.isLong() -> SSAPrimitiveType.LONG
            irType.isFloat() -> SSAPrimitiveType.FLOAT
            irType.isDouble() -> SSAPrimitiveType.DOUBLE
            irType.isUnit() -> SSAUnitType
            irType.isNothing() -> SSANothingType
            irType.isString() -> SSAStringType
            irType.classifierOrNull != null -> {
                val classifier = irType.getClass()!!
                SSAClass(classifier)
            }
            else -> SSAWrapperType(irType)
        }
    }
}

private fun getLocalName(parent: FqName, descriptor: IrDeclaration): Name {
    if (descriptor.isAnonymousObject) {
        return Name.identifier("anon")
    }

    return descriptor.name
}

private fun getFqName(descriptor: IrDeclaration): FqName {
    val parent = descriptor.parent
    val parentFqName = when (parent) {
        is IrPackageFragment -> parent.fqName
        is IrDeclaration -> getFqName(parent)
        else -> error(parent)
    }

    val localName = getLocalName(parentFqName, descriptor)
    return parentFqName.child(localName)
}

class SSADeclarationsMapper(val module: SSAModule, val typeMapper: SSATypeMapper) {

    fun mapFunction(func: IrFunction): SSACallable {
        // TODO: is it robust enough?
        val isVirtual = (func.isOverridable) && func is IrSimpleFunction
        module.index.functions.find { it.first == func }?.let {
            return it.second
        }
        module.index.imports.find { it.first == func }?.let {
            return it.second
        }
        val type = SSAFuncType(
                typeMapper.map(func.returnType),
                func.allParameters.map { typeMapper.map(it.type) }
        )
        val ssaFunction = if (isVirtual) {
            SSAVirtualFunction(getFqName(func).asString(), type, func)
        } else {
            val fn = SSAFunction(getFqName(func).asString(), type, func)
            module.imports += fn
            module.index.imports += func to fn
            fn
        }
        return ssaFunction
    }
}