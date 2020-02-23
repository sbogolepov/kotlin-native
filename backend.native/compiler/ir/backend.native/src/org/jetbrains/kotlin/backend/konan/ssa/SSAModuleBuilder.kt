package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.declarations.*

class SSAModuleIndex {
    val functions = mutableListOf<Pair<IrFunction, SSAFunction>>()
    val imports = mutableListOf<Pair<IrFunction, SSAFunction>>()
}

// Better to transform to some sort of SymbolTable
internal class SSAModuleBuilder(val context: Context) {

    private val index = SSAModuleIndex()

    private val typeMapper = SSATypeMapper(context)

    private fun createIndex(irModule: IrModuleFragment) {
        irModule.files.flatMap { it.declarations }.forEach { when (it) {
                is IrFunction -> {
                    index.functions += it to typeMapper.mapFunction(it)
                }
                is IrClass -> {
                    index.functions += indexClassMethods(it)
                }
            }
        }
    }

    fun build(irModule: IrModuleFragment): SSAModule {
        createIndex(irModule)
        val module = SSAModule(irModule.name.asString(), index)
        for ((ir, ssa) in index.functions) {
            val ssaFunctionBuilderImpl = SSAFunctionBuilderImpl(ssa, module, typeMapper)
            module.functions += ssaFunctionBuilderImpl.build(ir)
            (ssaFunctionBuilderImpl.generationContext as? GenerationContext.Function)?.complete(Unit)
        }
        return module
    }

    private fun indexClassMethods(irClass: IrClass): List<Pair<IrFunction, SSAFunction>> {
        val methods = mutableListOf<Pair<IrFunction, SSAFunction>>()
        for (decl in irClass.declarations) {
            if (decl is IrProperty) {
                decl.getter?.let {
                    val fn = typeMapper.mapFunction(it)
                    fn.metadata += "getter"
                    methods += it to fn
                }
                decl.setter?.let {
                    val fn = typeMapper.mapFunction(it)
                    fn.metadata += "setter"
                    methods += it to fn
                }
            }
        }
        return methods
    }
}