package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.declarations.*

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
        irModule.files.flatMap { it.declarations }.forEach { when (it) {
                is IrFunction -> {
                    index.functions += it to createSSAFuncFromIr(it)
                }
                is IrClass -> {
                    index.functions += indexClassMethods(it)
                }
            }
        }
        return index
    }

    fun build(irModule: IrModuleFragment): SSAModule {
        val index = createIndex(irModule)
        val module = SSAModule(irModule.name.asString(), index)
        for ((ir, ssa) in index.functions) {
            println("Generating ${ir.name}")
            val ssaFunctionBuilderImpl = SSAFunctionBuilderImpl(ssa, module)
            module.functions += ssaFunctionBuilderImpl.build(ir)
            (ssaFunctionBuilderImpl.generationContext as? GenerationContext.Function)?.let {
                it.complete(Unit)
            }
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