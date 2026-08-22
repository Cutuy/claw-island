package com.cutuy.clawisland.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.cutuy.clawisland.R
import com.cutuy.clawisland.databinding.OverlayCapsuleBinding
import com.cutuy.clawisland.databinding.OverlayExpandedBinding
import com.cutuy.clawisland.models.AgentStatus

/**
 * FloatingIslandController
 *
 vivo OriginOS won't promote third-party app notifications to the atomic island (it requires
 being on vivo's SuperX whitelist or using vivo's private SDK). So we draw our own capsule
 overlay via WindowManager.TYPE_APPLICATION_OVERLAY — visually equivalent from the user's
 POV, updates live from the bridge, and survives across the launcher / other apps.
 */
class FloatingIslandController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var capsuleBinding: OverlayCapsuleBinding? = null
    private var expandedBinding: OverlayExpandedBinding? = null

    private var capsuleView: View? = null
    private var expandedView: View? = null

    private var isExpanded = false

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(status: AgentStatus) {
        if (!canShow()) {
            Log.w(TAG, "Overlay permission denied — skipping floating island")
            return
        }
        if (capsuleView == null) {
            inflateCapsule()
        }
        updateCapsule(status)
        if (isExpanded && expandedBinding != null) {
            updateExpanded(status)
        }
    }

    fun hide() {
        runCatching { expandedView?.let { windowManager.removeView(it) } }
        runCatching { capsuleView?.let { windowManager.removeView(it) } }
        expandedBinding = null
        capsuleBinding = null
        expandedView = null
        capsuleView = null
        isExpanded = false
    }

    private fun inflateCapsule() {
        val inflater = LayoutInflater.from(context)
        val binding = OverlayCapsuleBinding.inflate(inflater)
        capsuleBinding = binding
        capsuleView = binding.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarOffsetY()
        }

        binding.root.setOnClickListener { toggleExpanded() }

        runCatching { windowManager.addView(binding.root, params) }
            .onFailure { Log.e(TAG, "addView(capsule) failed", it) }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        if (isExpanded) showExpanded() else hideExpanded()
    }

    private fun showExpanded() {
        if (expandedView != null) return
        val inflater = LayoutInflater.from(context)
        val binding = OverlayExpandedBinding.inflate(inflater)
        expandedBinding = binding
        expandedView = binding.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarOffsetY() + 80
        }

        runCatching { windowManager.addView(binding.root, params) }
            .onFailure { Log.e(TAG, "addView(expanded) failed", it) }
    }

    private fun hideExpanded() {
        runCatching { expandedView?.let { windowManager.removeView(it) } }
        expandedView = null
        expandedBinding = null
    }

    private fun updateCapsule(status: AgentStatus) {
        val b = capsuleBinding ?: return
        b.capsuleIcon.text = if (status.agent == "OpenCode") "🐾" else "🏝️"
        b.capsuleAgent.text = status.agent
        if (status.active) {
            val timer = if (status.formattedTime.isNotEmpty() && status.formattedTime != "00:00")
                " ${status.formattedTime}" else ""
            b.capsuleBadge.text = when (status.stateType) {
                "coding" -> "🔨$timer"
                "thinking" -> "🧠$timer"
                "executing" -> "⚡$timer"
                else -> "Active$timer"
            }
            b.capsuleBadge.setTextColor(0xFFFFB59D.toInt())
        } else {
            b.capsuleBadge.text = "Idle"
            b.capsuleBadge.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    private fun updateExpanded(status: AgentStatus) {
        val b = expandedBinding ?: return
        val timeStr = if (status.formattedTime.isNotEmpty()) " (${status.formattedTime})" else ""
        b.expandedTitle.text = "${status.agent} • ${status.model}$timeStr"
        b.expandedState.text = if (status.active) status.stateType.uppercase() else "IDLE"
        b.expandedContent.text = status.title
        b.expandedSubContent.text = status.detail
    }

    private fun statusBarOffsetY(): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
        return sb + 12
    }

    companion object {
        private const val TAG = "FloatingIsland"
    }
}