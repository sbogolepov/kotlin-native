package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanBackendContext
import org.jetbrains.kotlin.backend.konan.descriptors.isAbstract
import org.jetbrains.kotlin.backend.konan.ir.allParameters
import org.jetbrains.kotlin.backend.konan.ir.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.ir.isOverridable
import org.jetbrains.kotlin.backend.konan.ir.name
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class SSATypeMapper(val context: Context, val index: SSAModuleIndex) {
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
                createClass(classifier)
            }
            else -> SSAWrapperType(irType)
        }
    }

    fun mapClass(irClass: IrClass) = typeCache.getOrPut(irClass.defaultType) {
        createClass(irClass)
    } as SSAClass

    private fun createClass(irClass: IrClass): SSAClass {
        val isAbstact = irClass.isAbstract()
        val superTypes = irClass.superTypes.map { mapClass(it.getClass()!!) }
        val vtable = if (isAbstact) {
            val vtableBuilder = context.getVtableBuilder(irClass)
            vtableBuilder.vtableEntries.map { mapFunction(it.getImplementation(context)!!) }
        } else {
            emptyList()
        }
    }

    private fun mapFunctionType(irFunction: IrFunction): SSAFuncType =
        SSAFuncType(
                map(irFunction.returnType),
                irFunction.valueParameters.map { map(it.type) }
        )

    fun mapFunction(irFunction: IrFunction) =
            SSAFunction(irFunction.name.asString(), mapFunctionType(irFunction))
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

internal class SSADeclarationsMapper(val module: SSAModule, val typeMapper: SSATypeMapper) {

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
        return if (isVirtual) {
            SSAVirtualFunction(getFqName(func).asString(), type, func)
        } else {
            val fn = SSAFunction(getFqName(func).asString(), type, func)
            module.imports += fn
            module.index.imports += func to fn
            fn
        }
    }
}