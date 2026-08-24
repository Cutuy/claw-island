package com.cutuy.clawisland.service

import com.cutuy.clawisland.models.SessionEvent
import com.cutuy.clawisland.models.SessionsPayload
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared live store: the SSE service writes into it on every event (~2s),
 * MainActivity collects it with collectAsState(). Never throttled.
 */
object ClawIslandStore {
    val payload = MutableStateFlow(SessionsPayload())
    val connected = MutableStateFlow(false)

    /** Lifecycle event ring buffer (last 20), newest first. */
    val events = MutableStateFlow<List<SessionEvent>>(emptyList())

    fun pushEvents(newEvents: List<SessionEvent>) {
        if (newEvents.isEmpty()) return
        events.value = (newEvents.asReversed() + events.value).take(20)
    }
}
