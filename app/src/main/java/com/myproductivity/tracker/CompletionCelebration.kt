package com.myproductivity.tracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

object CompletionCelebration {
    fun show(context: Context, parent: ViewGroup, activityName: String) {
        lateinit var overlay: FrameLayout
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 22), dp(context, 22), dp(context, 22), dp(context, 16))
            background = GradientDrawable().apply {
                setColor(context.getColor(android.R.color.white))
                cornerRadius = dp(context, 22).toFloat()
            }
            elevation = dp(context, 12).toFloat()
            addView(TextView(context).apply {
                text = "Congratulations!"
                textSize = 23f
                setTextColor(context.getColor(R.color.ink))
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "You completed “$activityName”."
                textSize = 16f
                setTextColor(context.getColor(R.color.ink))
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 8), 0, dp(context, 8))
            })
            addView(TextView(context).apply {
                text = "DONE"
                textSize = 14f
                setTextColor(context.getColor(R.color.brand))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 4), dp(context, 4))
                setOnClickListener { parent.removeView(overlay) }
            })
        }

        overlay = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { parent.removeView(this) }
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    leftMargin = dp(context, 20)
                    rightMargin = dp(context, 20)
                }
            )
            addView(FallingConfettiView(context) { card }, FrameLayout.LayoutParams(-1, -1))
            card.setOnClickListener { }
        }
        parent.addView(overlay, ViewGroup.LayoutParams(-1, -1))

        card.scaleX = 0.82f
        card.scaleY = 0.82f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).start()
        overlay.getChildAt(1).let { (it as FallingConfettiView).start() }
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 220)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
        Handler(Looper.getMainLooper()).postDelayed({
            if (overlay.parent === parent) parent.removeView(overlay)
        }, 4_000)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class FallingConfettiView(
        context: Context,
        private val card: () -> View
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(
            0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
            0xFFFDD835.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt()
        )
        private val pieces = List(48) {
            Piece(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 0.8f + 0.5f, colors.random())
        }
        private var progress = 0f

        fun start() {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2_400
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val panel = card()
            if (panel.width > 0 && panel.height > 0) {
                val location = IntArray(2)
                panel.getLocationOnScreen(location)
                val overlayLocation = IntArray(2)
                getLocationOnScreen(overlayLocation)
                val left = (location[0] - overlayLocation[0]).toFloat()
                val top = (location[1] - overlayLocation[1]).toFloat()
                val cutout = Path().apply {
                    addRoundRect(
                        RectF(left, top, left + panel.width, top + panel.height),
                        dp(context, 22).toFloat(),
                        dp(context, 22).toFloat(),
                        Path.Direction.CW
                    )
                }
                canvas.save()
                canvas.clipOutPath(cutout)
            } else {
                canvas.save()
            }
            pieces.forEach { piece ->
                val y = ((progress * piece.speed + piece.offset) % 1.2f) * height - height * 0.1f
                val x = piece.x * width + kotlin.math.sin((progress + piece.offset) * 9f) * width * 0.035f
                paint.color = piece.color
                canvas.save()
                canvas.rotate(progress * 720f + piece.offset * 360f, x, y)
                canvas.drawRect(x, y, x + dp(context, 6), y + dp(context, 10), paint)
                canvas.restore()
            }
            canvas.restore()
        }

        private data class Piece(val x: Float, val offset: Float, val speed: Float, val color: Int)
    }
}
