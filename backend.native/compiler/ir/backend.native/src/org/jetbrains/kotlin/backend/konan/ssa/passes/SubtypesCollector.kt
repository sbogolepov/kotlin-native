package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAModule
import org.jetbrains.kotlin.ir.types.IrType

class SubTypes() {
    fun getSubClasses(irType: IrType): Set<IrType> {
        TODO()
    }
}

class SubtypesCollector : ModulePass<SubTypes> {
    override val name: String = "Class Hierarchy collection"

    lateinit var classHierarchy: MutableMap<IrType, MutableList<IrType>>

    override fun apply(module: SSAModule): SubTypes {
        classHierarchy = mutableMapOf()

        return SubTypes()
    }
}