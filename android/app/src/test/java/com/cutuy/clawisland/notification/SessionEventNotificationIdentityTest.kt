package com.cutuy.clawisland.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionEventNotificationIdentityTest {
    @Test
    fun sameSessionUsesStableNotificationIdAcrossEvents() {
        assertEquals(
            SessionEventNotificationIdentity.notificationId("codex:abc", "event-1"),
            SessionEventNotificationIdentity.notificationId("codex:abc", "event-2")
        )
    }

    @Test
    fun providerQualifiedSessionsRemainIndependent() {
        assertNotEquals(
            SessionEventNotificationIdentity.notificationId("codex:abc", "event-1"),
            SessionEventNotificationIdentity.notificationId("opencode:abc", "event-2")
        )
    }

    @Test
    fun missingSessionFallsBackToStableEventIdentity() {
        assertEquals(
            SessionEventNotificationIdentity.notificationId(null, "event-1"),
            SessionEventNotificationIdentity.notificationId("", "event-1")
        )
    }
}
