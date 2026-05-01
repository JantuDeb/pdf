package com.thestudypath.pdf.walkthrough

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import kotlin.math.max

data class SpotlightStep(
    val target: View? = null,
    val targetRectProvider: (() -> RectF?)? = null,
    val title: String,
    val message: String,
    val cardPlacement: SpotlightCardPlacement = SpotlightCardPlacement.AUTO,
)

enum class SpotlightCardPlacement {
    AUTO,
    TOP,
}

class PdfAnnotationWalkthrough(
    private val activity: Activity,
) {
    private var overlay: SpotlightOverlayView? = null
    private var stepIndex = 0
    private var steps: List<SpotlightStep> = emptyList()
    private var onFinished: (() -> Unit)? = null

    fun start(steps: List<SpotlightStep>, onFinished: () -> Unit) {
        if (steps.isEmpty()) {
            onFinished()
            return
        }

        dismiss(markFinished = false)
        this.steps = steps
        this.onFinished = onFinished
        stepIndex = 0

        val root = activity.window.decorView as? ViewGroup ?: return
        overlay = SpotlightOverlayView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            onNext = { showNextStep() }
            onSkip = { dismiss(markFinished = true) }
        }
        root.addView(overlay)
        showStep()
    }

    fun dismiss(markFinished: Boolean) {
        overlay?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        if (markFinished) {
            onFinished?.invoke()
        }
        onFinished = null
        steps = emptyList()
        stepIndex = 0
    }

    private fun showNextStep() {
        stepIndex += 1
        if (stepIndex >= steps.size) {
            dismiss(markFinished = true)
        } else {
            showStep()
        }
    }

    private fun showStep() {
        val current = steps.getOrNull(stepIndex) ?: return
        overlay?.showStep(
            step = current,
            isLastStep = stepIndex == steps.lastIndex,
        )
    }
}

private class SpotlightOverlayView(
    context: Activity,
) : View(context) {
    var onNext: (() -> Unit)? = null
    var onSkip: (() -> Unit)? = null

    private var step: SpotlightStep? = null
    private var isLastStep = false
    private var targetRect = RectF()
    private var cardRect = RectF()
    private var nextRect = RectF()
    private var skipRect = RectF()
    private var bodyLines: List<String> = emptyList()
    private var isTouchingAction = false
    private var keepTargetFresh = false

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 0, 0, 0)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 35, 55)
        textSize = sp(20f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 74, 92)
        textSize = sp(15f)
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 88, 180)
        textSize = sp(15f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val skipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 104, 120)
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showStep(step: SpotlightStep, isLastStep: Boolean) {
        this.step = step
        this.isLastStep = isLastStep
        contentDescription = "${step.title}. ${step.message}"
        keepTargetFresh = true
        post {
            updateTargetRect()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = step ?: return
        updateTargetRect()

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val radius = if (targetRect.height() > targetRect.width() * 1.8f) 28f else 44f
        canvas.drawRoundRect(targetRect, radius, radius, clearPaint)
        canvas.drawRoundRect(targetRect, radius, radius, outlinePaint)

        layoutCard()
        canvas.drawRoundRect(cardRect, 18f, 18f, cardPaint)

        val horizontalPadding = 22f
        var baseline = cardRect.top + 22f - titlePaint.fontMetrics.ascent
        canvas.drawText(current.title, cardRect.left + horizontalPadding, baseline, titlePaint)

        baseline += titleLineHeight() + 8f
        bodyLines.forEach { line ->
            canvas.drawText(line, cardRect.left + horizontalPadding, baseline, bodyPaint)
            baseline += bodyLineHeight()
        }

        val buttonTop = cardRect.bottom - 56f
        skipRect.set(cardRect.left + 10f, buttonTop + 8f, cardRect.left + 110f, cardRect.bottom - 8f)
        nextRect.set(cardRect.right - 122f, buttonTop + 8f, cardRect.right - 10f, cardRect.bottom - 8f)

        canvas.drawText("Skip", skipRect.centerX(), skipRect.centerY() + 6f, skipPaint)
        canvas.drawText(
            if (isLastStep) "Got it" else "Next",
            nextRect.centerX(),
            nextRect.centerY() + 6f,
            actionPaint,
        )

        if (keepTargetFresh) {
            postInvalidateDelayed(TARGET_REFRESH_MS)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isOnAction = nextRect.contains(event.x, event.y) || skipRect.contains(event.x, event.y)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchingAction = isOnAction
                isOnAction
            }

            MotionEvent.ACTION_UP -> {
                if (!isTouchingAction) return false
                isTouchingAction = false
                when {
                    nextRect.contains(event.x, event.y) -> onNext?.invoke()
                    skipRect.contains(event.x, event.y) -> onSkip?.invoke()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                isTouchingAction = false
                false
            }

            else -> isTouchingAction
        }
    }

    private fun updateTargetRect() {
        val current = step ?: return
        val providedRect = current.targetRectProvider?.invoke()
        if (providedRect != null) {
            targetRect.set(providedRect)
            return
        }

        val target = current.target
        if (target == null || target.width == 0 || target.height == 0) {
            targetRect.set(width * 0.15f, height * 0.35f, width * 0.85f, height * 0.55f)
            return
        }

        val targetLocation = IntArray(2)
        val ownLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)
        getLocationOnScreen(ownLocation)

        val padding = 10f
        targetRect.set(
            targetLocation[0] - ownLocation[0] - padding,
            targetLocation[1] - ownLocation[1] - padding,
            targetLocation[0] - ownLocation[0] + target.width + padding,
            targetLocation[1] - ownLocation[1] + target.height + padding,
        )
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
    }

    private fun layoutCard() {
        val margin = 18f
        val safeTop = rootWindowInsets
            ?.getInsets(WindowInsets.Type.statusBars())
            ?.top
            ?.toFloat()
            ?.plus(margin)
            ?: margin
        val cardWidth = max(280f, width - margin * 2)
        val horizontalPadding = 22f
        bodyLines = step?.let {
            wrapText(it.message, cardWidth - horizontalPadding * 2, bodyPaint)
        } ?: emptyList()
        val cardHeight = 118f + titleLineHeight() + bodyLines.size * bodyLineHeight()
        val topBelowTarget = targetRect.bottom + 18f
        val topAboveTarget = targetRect.top - cardHeight - 18f
        val top = when (step?.cardPlacement) {
            SpotlightCardPlacement.TOP -> safeTop + 64f * resources.displayMetrics.density
            else -> when {
                topBelowTarget + cardHeight < height - margin && targetRect.centerY() < height * 0.52f -> topBelowTarget
                topAboveTarget > safeTop -> topAboveTarget
                topBelowTarget + cardHeight < height - margin -> topBelowTarget
                else -> max(safeTop, height - cardHeight - margin)
            }
        }
        val left = (width - cardWidth) / 2f
        cardRect.set(left, top, left + cardWidth, top + cardHeight)
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.take(4)
    }

    private fun titleLineHeight(): Float {
        val metrics = titlePaint.fontMetrics
        return metrics.descent - metrics.ascent
    }

    private fun bodyLineHeight(): Float {
        val metrics = bodyPaint.fontMetrics
        return metrics.descent - metrics.ascent + 4f
    }

    companion object {
        private const val TARGET_REFRESH_MS = 180L
    }
}
