package org.jetbrains.kotlin.backend.konan.ssa

class SSASlotTracker() {

    private val tracker = mutableListOf<SSAValue>()

    fun track(value: SSAValue) = tracker.add(value)

    fun slot(value: SSAValue) = tracker.indexOf(value).also {
        if (it == -1) println("untracked value: $value")
    }

    fun isTracked(value: SSAValue) = value in tracker
}