package com.cutuy.clawisland.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentStatus(
    @SerialName("active") val active: Boolean = false,
    @SerialName("agent") val agent: String = "OpenClaw",
    @SerialName("status") val status: String = "idle",
    @SerialName("state_type") val stateType: String = "idle", // coding, thinking, executing, idle
    @SerialName("title") val title: String = "Idle",
    @SerialName("detail") val detail: String = "",
    @SerialName("model") val model: String = "ox-alpha-free",
    @SerialName("proc_count") val procCount: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = 0L
)
