package com.cutuy.clawisland.notification

object SessionEventNotificationIdentity {
    const val GROUP_KEY = "com.cutuy.clawisland.SESSION_EVENTS"

    fun notificationId(sessionId: String?, eventId: String): Int {
        val identity = sessionId?.takeIf { it.isNotBlank() } ?: eventId
        // Keep lifecycle IDs in a dedicated positive namespace, away from the
        // fixed foreground-service notification IDs.
        return ("claw-session:$identity".hashCode() and 0x3fffffff) or 0x40000000
    }
}
