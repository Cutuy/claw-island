package com.cutuy.clawisland.notification

import com.cutuy.clawisland.models.SessionInfo
import com.cutuy.clawisland.models.SessionsPayload

/**
 * Derives notification-center content (title / text / subtext / big text /
 * progress) from a SessionsPayload. Shared by the vivo, pixel and standard
 * notification builders so all render paths tell the same story.
 */
object SessionsContent {

    data class Content(
        val title: String,
        val text: String,
        val subtext: String,
        val bigText: String,
        val activeCount: Int,
        val top: SessionInfo?,
        val hasProgress: Boolean,      // show a determinate progress bar
        val progressMax: Int,
        val progressValue: Int,
        val indeterminate: Boolean     // no todo → indeterminate bar while active
    )

    fun build(payload: SessionsPayload): Content {
        val active = payload.active
        return when {
            active.isEmpty() -> idleContent(payload)
            active.size == 1 -> singleContent(active.first())
            else -> multiContent(active)
        }
    }

    private fun idleContent(payload: SessionsPayload): Content {
        val dayAgo = payload.nowTs - 24 * 3600_000L
        val recentCount = payload.recent.count { it.updatedAt >= dayAgo }
        return Content(
            title = "All agents idle",
            text = "Standing by",
            subtext = "$recentCount sessions in last 24h",
            bigText = "Standing by for instructions.\n\n$recentCount sessions in last 24h.",
            activeCount = 0,
            top = null,
            hasProgress = false,
            progressMax = 0,
            progressValue = 0,
            indeterminate = false
        )
    }

    private fun singleContent(s: SessionInfo): Content {
        val title = "${sourceIcon(s)} ${s.repo} · ${s.title}"
        val text = s.lastActivity.take(120).ifEmpty { "Working…" }
        val subtext = "${s.repo} · ${s.model} · ⏱ ${s.formattedTime} · ${fmtTokens(s.tokensInput)}/${fmtTokens(s.tokensOutput)}"
        val big = buildString {
            append(text)
            if (s.todo.total > 0) {
                append("\n\n")
                s.todo.items.take(10).forEach { item ->
                    append(
                        when (item.status) {
                            "completed" -> "✓ "
                            "in_progress" -> "● "
                            else -> "◌ "
                        }
                    )
                    append(item.content)
                    append('\n')
                }
            }
            append("\n${tokenSummary(s)}")
        }
        return Content(
            title = title,
            text = text,
            subtext = subtext,
            bigText = big,
            activeCount = 1,
            top = s,
            hasProgress = s.todo.total > 0,
            progressMax = s.todo.total,
            progressValue = s.todo.completed,
            indeterminate = s.todo.total == 0
        )
    }

    private fun multiContent(active: List<SessionInfo>): Content {
        val first = active.first()
        val title = "${active.size} agents active"
        val text = first.lastActivity.take(120).ifEmpty { "Working…" }
        val repos = active.joinToString(" · ") { it.repo }.let { if (it.length > 60) it.take(57) + "..." else it }
        val big = buildString {
            active.forEach { s ->
                append("${sourceIcon(s)} ${s.repo} — ${s.title} (${s.formattedTime})\n")
                if (s.todo.total > 0) {
                    append("   ✓${s.todo.completed}/${s.todo.total} · ●${s.todo.inProgress} in progress\n")
                }
            }
            append("\nin ${fmtTokens(first.tokensInput)} · out ${fmtTokens(first.tokensOutput)} · $${fmtCost(first.cost)}")
        }
        return Content(
            title = title,
            text = text,
            subtext = repos,
            bigText = big,
            activeCount = active.size,
            top = first,
            hasProgress = first.todo.total > 0,
            progressMax = first.todo.total,
            progressValue = first.todo.completed,
            indeterminate = first.todo.total == 0
        )
    }

    fun fmtTokens(n: Long): String = when {
        n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000L -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    fun fmtCost(c: Double): String = when {
        c >= 1.0 -> String.format("%.2f", c)
        c >= 0.01 -> String.format("%.3f", c)
        else -> String.format("%.4f", c)
    }

    private fun sourceIcon(s: SessionInfo) = if (s.source == "codex") "◈" else "🔨"

    private fun tokenSummary(s: SessionInfo): String = if (s.tokensTotal > 0) {
        "tokens ${fmtTokens(s.tokensTotal)}"
    } else {
        "in ${fmtTokens(s.tokensInput)} · out ${fmtTokens(s.tokensOutput)} · $${fmtCost(s.cost)}"
    }
}
