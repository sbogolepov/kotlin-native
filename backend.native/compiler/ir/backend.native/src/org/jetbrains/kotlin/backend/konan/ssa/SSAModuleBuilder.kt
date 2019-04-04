package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.ir.allParameters
import org.jetbrains.kotlin.backend.konan.ir.isAnonymousObject
import org.jetbrains.kotlin.backend.konan.ir.name
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe

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

class SSATypeMapper {
    fun map(irType: IrType): SSAType {
        return if (irType.isPrimitiveType()) when {
            irType.isBoolean() -> SSAPrimitiveType.BOOL
            irType.isByte() -> SSAPrimitiveType.BYTE
            irType.isChar() -> SSAPrimitiveType.CHAR
            irType.isShort() -> SSAPrimitiveType.SHORT
            irType.isInt() -> SSAPrimitiveType.INT
            irType.isLong() -> SSAPrimitiveType.LONG
            irType.isFloat() -> SSAPrimitiveType.FLOAT
            irType.isDouble() -> SSAPrimitiveType.DOUBLE
            else -> TODO("Unsupported primitive type: ${irType.classifierOrNull?.descriptor?.fqNameUnsafe?.asString()}")
        } else {
            SSAWrapperType(irType)
        }
    }
}

class SSADeclarationsMapper(val module: SSAModule, val typeMapper: SSATypeMapper) {

    fun mapFunction(func: IrFunction): SSAFunction {
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
        val ssaFunction = SSAFunction(getFqName(func).asString(), type, func)
        module.imports += ssaFunction
        module.index.imports += func to ssaFunction
        return ssaFunction
    }
}

class SSAModuleIndex {
    val functions = mutableListOf<Pair<IrFunction, SSAFunction>>()
    val imports = mutableListOf<Pair<IrFunction, SSAFunction>>()
}

// Better to transform to some sort of SymbolTable
class SSAModuleBuilder {

    private val typeMapper = SSATypeMapper()

    private fun createSSAFuncFromIr(irFunction: IrFunction): SSAFunction {
        val name = irFunction.name.asString()
        val type = SSAFuncType(
                typeMapper.map(irFunction.returnType),
                irFunction.valueParameters.map { typeMapper.map(it.type) }
        )
        return SSAFunction(name, type)
    }

    private fun createIndex(irModule: IrModuleFragment): SSAModuleIndex {
        val index = SSAModuleIndex()
        for (irFile in irModule.files) {
            for (decl in irFile.declarations) {
                if (decl is IrFunction) {
                    val ssa = createSSAFuncFromIr(decl)
                    index.functions += decl to ssa
                }
                if (decl is IrClass) {
                    index.functions += indexClassMethods(decl)
                }
            }
        }
        return index
    }

    fun build(irModule: IrModuleFragment): SSAModule {
        val index = createIndex(irModule)
        val module = SSAModule(irModule.name.asString(), index)
        for ((ir, ssa) in index.functions) {
            module.functions += SSAFunctionBuilderImpl(ssa, module).build(ir)
        }
        return module
    }

    private fun indexClassMethods(irClass: IrClass): List<Pair<IrFunction, SSAFunction>> {
        val methods = mutableListOf<Pair<IrFunction, SSAFunction>>()
        for (decl in irClass.declarations) {
            if (decl is IrProperty) {
                decl.getter?.let {
                    val fn = createSSAFuncFromIr(it)
                    fn.metadata += "getter"
                    methods += it to fn
                }
                decl.setter?.let {
                    val fn = createSSAFuncFromIr(it)
                    fn.metadata += "setter"
                    methods += it to fn
                }
            }
        }
        return methods
    }
}