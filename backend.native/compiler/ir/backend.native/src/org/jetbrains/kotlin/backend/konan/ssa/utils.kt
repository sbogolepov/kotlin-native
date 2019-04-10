package org.jetbrains.kotlin.backend.konan.ssa

class WorkList<T>(initialData: List<T>) {
    private val toProcess = initialData.toMutableList()
    private val processed = mutableSetOf<T>()

    fun get(): T {
        val item = toProcess.get(0)
        toProcess.removeAt(0)
        processed += item
        return item
    }

    fun add(item: T) {
        if (item !in processed) {
            toProcess += item
            processed += item
        }
    }

    fun isEmpty() =
            toProcess.isEmpty()
}