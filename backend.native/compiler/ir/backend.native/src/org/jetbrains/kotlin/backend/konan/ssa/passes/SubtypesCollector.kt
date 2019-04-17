package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAAlloc
import org.jetbrains.kotlin.backend.konan.ssa.SSAClass
import org.jetbrains.kotlin.backend.konan.ssa.SSAModule

class TypeCones(private val classHierarchy: Map<SSAClass, List<SSAClass>>) {
    fun getSubClasses(ssaClass: SSAClass): Set<SSAClass> {
        return classHierarchy.getValue(ssaClass).toSet()
    }
}

class TypeConesCollector : ModulePass<TypeCones> {
    override val name: String = "Class Hierarchy collection"

    override fun apply(module: SSAModule): TypeCones {
        val classHierarchy = mutableMapOf<SSAClass, MutableList<SSAClass>>()
        module.functions.asSequence()
                .flatMap { it.blocks.asSequence() }
                .flatMap { it.body.asSequence() }
                .filterIsInstance<SSAAlloc>()
                .forEach { alloc ->
                    val type = alloc.type
                    if(type is SSAClass) {
                        type.superTypes.forEach { superType ->
                            classHierarchy.getOrPut(superType, ::mutableListOf) += type
                        }
                    }
                }
        return TypeCones(classHierarchy)
    }

}