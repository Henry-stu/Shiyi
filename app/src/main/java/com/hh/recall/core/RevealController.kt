package com.hh.recall.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RevealController(private val scope: CoroutineScope, private val durationMs: Long = 5_000L) {
    private val visible = MutableStateFlow<Set<String>>(emptySet())
    val revealed = visible.asStateFlow()
    private val timers = mutableMapOf<String, Job>()
    private var generation = 0L

    fun toggle(id: String) {
        timers.remove(id)?.cancel()
        if (id in visible.value) {
            visible.value = visible.value - id
        } else {
            visible.value = visible.value + id
            val token = generation
            timers[id] = scope.launch {
                delay(durationMs)
                if (token == generation) {
                    visible.value = visible.value - id
                    timers.remove(id)
                }
            }
        }
    }

    fun toggleAll(ids: Set<String>) {
        val hide = ids.all { it in visible.value }
        cancelTimers()
        visible.value = if (hide) emptySet() else ids
    }

    private fun cancelTimers() {
        generation++
        timers.values.forEach { it.cancel() }
        timers.clear()
    }

    fun reset() { cancelTimers(); visible.value = emptySet() }
}
