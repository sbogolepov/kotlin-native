package org.jetbrains.kotlin.backend.konan.ssa

class SSASlotTracker() {

    private val tracker = mutableListOf<SSAValue>()

    fun track(value: SSAValue) = tracker.add(value)

    fun slot(value: SSAValue) = tracker.indexOf(value)

    fun isTracked(value: SSAValue) = value in tracker
}