package org.jetbrains.kotlin.backend.konan.ssa.passes

class NestedMap<K1, K2, V> {
    private val storage = mutableMapOf<K1, MutableMap<K2, V>>()

    operator fun get(k1: K1, k2: K2): V? =
        storage.getOrPut(k1, ::mutableMapOf)[k2]

    operator fun set(k1: K1, k2: K2, value: V) {
            storage.getOrPut(k1, ::mutableMapOf)[k2] = value
    }

    fun getOrPut(k1: K1, k2: K2, defaultValue: () -> V): V =
            storage.getOrPut(k1, ::mutableMapOf).getOrPut(k2, defaultValue)
}