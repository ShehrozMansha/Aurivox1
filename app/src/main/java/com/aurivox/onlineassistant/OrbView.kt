package com.aurivox.onlineassistant

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView

class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    private var pulseX: ObjectAnimator? = null
    private var pulseY: ObjectAnimator? = null

    fun setState(newState: State) {
        val (from, to, dur) = when (newState) {
            State.IDLE -> Triple(1f, 1.04f, 1600L)
            State.LISTENING -> Triple(1f, 1.09f, 900L)
            State.PROCESSING -> Triple(1f, 1.07f, 1100L)
            State.SPEAKING -> Triple(1f, 1.08f, 1000L)
        }
        animatePulse(from, to, dur)
    }

    fun startRotation() {
        ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
            duration = 7000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun animatePulse(from: Float, to: Float, dur: Long) {
        pulseX?.cancel(); pulseY?.cancel()
        scaleX = 1f; scaleY = 1f
        pulseX = ObjectAnimator.ofFloat(this, "scaleX", from, to, from).apply {
            duration = dur; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
        pulseY = ObjectAnimator.ofFloat(this, "scaleY", from, to, from).apply {
            duration = dur; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }
}
