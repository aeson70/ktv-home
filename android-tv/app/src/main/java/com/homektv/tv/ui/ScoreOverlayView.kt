package com.homektv.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 演唱完成 AI 评分浮层：大号分数 + 点评，弹出动画，8 秒后自动消失。
 *
 * AI performance-score overlay: big score number with a comment,
 * pop-in animation, auto-dismiss after 8 seconds.
 */
class ScoreOverlayView(context: Context) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val card: LinearLayout
    private val scoreText: TextView
    private val titleText: TextView
    private val commentText: TextView
    private val hideRunnable = Runnable { hide() }

    init {
        isClickable = false
        isFocusable = false
        visibility = View.GONE
        val density = resources.displayMetrics.density
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((36 * density).toInt(), (24 * density).toInt(), (36 * density).toInt(), (24 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 10, 10, 18))
                cornerRadius = 28f * density
                setStroke((2 * density).toInt(), Color.argb(255, 255, 213, 79))
            }
        }
        titleText = TextView(context).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        scoreText = TextView(context).apply {
            textSize = 84f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 255, 213, 79))
            gravity = Gravity.CENTER
        }
        commentText = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        card.addView(titleText)
        card.addView(scoreText)
        card.addView(commentText)
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 显示评分；重复调用会刷新内容并重新计时。 */
    fun show(score: Int, comment: String, songTitle: String) {
        main.removeCallbacks(hideRunnable)
        titleText.text = if (songTitle.isBlank()) "🎤 演唱评分" else "🎤 《$songTitle》演唱评分"
        scoreText.text = "$score 分"
        commentText.text = comment
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        main.postDelayed(hideRunnable, 8_000L)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        animate().alpha(0f).setDuration(300).withEndAction { visibility = View.GONE }.start()
    }
}
