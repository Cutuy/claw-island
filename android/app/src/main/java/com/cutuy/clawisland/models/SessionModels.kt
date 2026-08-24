package com.cutuy.clawisland.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    @SerialName("content") val content: String = "",
    @SerialName("status") val status: String = "pending",  // pending | in_progress | completed
    @SerialName("priority") val priority: String = "medium"
)

@Serializable
data class TodoStats(
    @SerialName("total") val total: Int = 0,
    @SerialName("completed") val completed: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    @SerialName("pending") val pending: Int = 0,
    @SerialName("items") val items: List<TodoItem> = emptyList()
)

@Serializable
data class SessionInfo(
    @SerialName("id") val id: String = "",
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source") val source: String = "opencode",
    @SerialName("title") val title: String = "Unknown",
    @SerialName("repo") val repo: String = "",
    @SerialName("directory") val directory: String = "",
    @SerialName("agent") val agent: String = "build",
    @SerialName("model") val model: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int = 0,
    @SerialName("formatted_time") val formattedTime: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("tokens_input") val tokensInput: Long = 0,
    @SerialName("tokens_output") val tokensOutput: Long = 0,
    @SerialName("tokens_reasoning") val tokensReasoning: Long = 0,
    @SerialName("tokens_cache_read") val tokensCacheRead: Long = 0,
    @SerialName("tokens_cache_write") val tokensCacheWrite: Long = 0,
    @SerialName("tokens_total") val tokensTotal: Long = 0,
    @SerialName("cost") val cost: Double = 0.0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("last_activity") val lastActivity: String = "",
    @SerialName("git_branch") val gitBranch: String = "",
    @SerialName("reasoning_effort") val reasoningEffort: String = "",
    @SerialName("phase") val phase: String = "",
    @SerialName("last_action") val lastAction: String = "",
    @SerialName("todo") val todo: TodoStats = TodoStats(),
    @SerialName("proc_count") val procCount: Int = 0
)

@Serializable
data class SessionEvent(
    @SerialName("id") val id: String = "",
    @SerialName("seq") val seq: Long = 0,
    @SerialName("type") val type: String = "session_started",
    @SerialName("ts") val ts: Long = 0,
    @SerialName("session") val session: SessionInfo? = null
)

@Serializable
data class SessionsPayload(
    @SerialName("now_ts") val nowTs: Long = 0,
    @SerialName("active") val active: List<SessionInfo> = emptyList(),
    @SerialName("recent") val recent: List<SessionInfo> = emptyList(),
    @SerialName("events") val events: List<SessionEvent> = emptyList(),
    // v2.2 data diet: delta=false ticks are ~80-byte heartbeats (content
    // unchanged server-side) — keep the last full payload, don't clear state.
    @SerialName("delta") val delta: Boolean = true,
    @SerialName("max_seq") val maxSeq: Long = 0
) {
    val anyActive: Boolean get() = active.isNotEmpty()
}
