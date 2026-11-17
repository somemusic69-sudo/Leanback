package com.leanback.app

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onMove(dxDp: Float, dyDp: Float)
        fun onClick(button: String)
        fun onMouseDown(button: String)
        fun onMouseUp(button: String)
        fun onScroll(dxDp: Float, dyDp: Float)
        fun onZoomChanged(level: Float)
        fun onTouchInteract()
    }

    private enum class TwoFingerMode {
        NONE,
        DECIDING,
        SCROLLING,
        PINCH_ZOOM,
    }

    var listener: Listener? = null
    var sensitivity: Float = 1.25f
    var naturalScroll: Boolean = true
    var tapToClickEnabled: Boolean = true
    var singleFingerScrollMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                removeCallbacks(pendingSingleTapRunnable)
            }
        }
    var multiFingerEnabled: Boolean = true
    var currentZoomLevel: Float = 1.0f
        private set

    private val density = resources.displayMetrics.density.coerceAtLeast(1f)

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedBeyondSlop = false

    private var maxPointerCount = 0
    private var multiFingerDownTime = 0L
    private var startMidX = 0f
    private var startMidY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var startSpanDp = 0f
    private var lastSpanDp = 0f
    private var anchorSpanDp = 0f
    private var anchorZoom = 1.0f
    private var lastSentZoom = 1.0f
    private var twoFingerMode = TwoFingerMode.NONE
    private var twoFingerDidGesture = false
    private var waitForAllUp = false

    private var isDragging = false
    private var isTapDragCandidate = false
    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var hasPendingSingleTap = false

    private val pendingSingleTapRunnable = Runnable {
        hasPendingSingleTap = false
        listener?.onClick("left")
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }

    fun resetZoom() {
        currentZoomLevel = 1.0f
        anchorZoom = 1.0f
        lastSentZoom = 1.0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val now = event.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchInteract()
                activePointerId = event.getPointerId(0)
                downTime = now
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                movedBeyondSlop = false
                maxPointerCount = 1
                twoFingerMode = TwoFingerMode.NONE
                twoFingerDidGesture = false
                waitForAllUp = false
                isDragging = false

                val sinceLastTap = now - lastTapUpTime
                val distFromLastTapDp = hypot(downX - lastTapUpX, downY - lastTapUpY) / density
                isTapDragCandidate =
                    tapToClickEnabled &&
                    !singleFingerScrollMode &&
                    sinceLastTap in 1..240 &&
                    distFromLastTapDp < 28f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multiFingerEnabled) {
                    waitForAllUp = true
                    return true
                }
                removeCallbacks(pendingSingleTapRunnable)
                hasPendingSingleTap = false

                val count = event.pointerCount
                if (count > maxPointerCount) {
                    maxPointerCount = count
                }
                if (isDragging) {
                    listener?.onMouseUp("left")
                    isDragging = false
                }
                isTapDragCandidate = false
                waitForAllUp = true

                if (count == 2) {
                    multiFingerDownTime = now
                    startMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    startMidY = (event.getY(0) + event.getY(1)) * 0.5f
                    lastMidX = startMidX
                    lastMidY = startMidY
                    startSpanDp = spanBetween(event).coerceAtLeast(12f)
                    lastSpanDp = startSpanDp
                    anchorSpanDp = startSpanDp
                    anchorZoom = currentZoomLevel
                    lastSentZoom = currentZoomLevel
                    twoFingerMode = TwoFingerMode.DECIDING
                    twoFingerDidGesture = false
                } else if (count >= 3) {
                    twoFingerDidGesture = true
                    twoFingerMode = TwoFingerMode.NONE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (multiFingerEnabled && event.pointerCount == 2) {
                        val midX = (event.getX(0) + event.getX(1)) * 0.5f
                        val midY = (event.getY(0) + event.getY(1)) * 0.5f
                        val dxDp = (midX - lastMidX) / density
                        val dyDp = (midY - lastMidY) / density
                        lastMidX = midX
                        lastMidY = midY

                        val spanDp = spanBetween(event).coerceAtLeast(12f)
                        lastSpanDp = spanDp

                        when (twoFingerMode) {
                            TwoFingerMode.DECIDING -> {
                                val midDeltaDp = hypot(midX - startMidX, midY - startMidY) / density
                                val spanDeltaDp = abs(spanDp - startSpanDp)

                                if (spanDeltaDp > PINCH_SLOP_DP && spanDeltaDp >= midDeltaDp * 0.55f) {
                                    twoFingerMode = TwoFingerMode.PINCH_ZOOM
                                    twoFingerDidGesture = true
                                    anchorSpanDp = spanDp
                                    anchorZoom = currentZoomLevel
                                    lastSentZoom = currentZoomLevel
                                } else if (midDeltaDp > SCROLL_SLOP_DP) {
                                    twoFingerMode = TwoFingerMode.SCROLLING
                                    twoFingerDidGesture = true
                                    val sign = if (naturalScroll) 1f else -1f
                                    listener?.onScroll(dxDp * sign, dyDp * sign)
                                }
                            }

                            TwoFingerMode.PINCH_ZOOM -> {
                                val ratio = (spanDp / anchorSpanDp).coerceIn(0.1f, 8f)
                                val rawZoom = anchorZoom * ratio.pow(1.35f)
                                val clamped = rawZoom.coerceIn(1.0f, 5.0f)
                                currentZoomLevel = if (clamped < 1.04f) 1.0f else clamped

                                if (abs(currentZoomLevel - lastSentZoom) >= 0.02f ||
                                    (currentZoomLevel == 1.0f && lastSentZoom != 1.0f)
                                ) {
                                    lastSentZoom = currentZoomLevel
                                    listener?.onZoomChanged(currentZoomLevel)
                                }

                                if (dxDp != 0f || dyDp != 0f) {
                                    listener?.onMove(dxDp * sensitivity, dyDp * sensitivity)
                                }
                            }

                            TwoFingerMode.SCROLLING -> {
                                val sign = if (naturalScroll) 1f else -1f
                                listener?.onScroll(dxDp * sign, dyDp * sign)
                            }

                            TwoFingerMode.NONE -> Unit
                        }
                    }
                    return true
                }

                if (waitForAllUp) return true

                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true

                val curX = event.getX(idx)
                val curY = event.getY(idx)
                val totalDistDp = hypot(curX - downX, curY - downY) / density
                if (!movedBeyondSlop && totalDistDp > 5.5f) {
                    movedBeyondSlop = true
                    removeCallbacks(pendingSingleTapRunnable)
                    hasPendingSingleTap = false
                    if (isTapDragCandidate && !isDragging) {
                        isDragging = true
                        listener?.onMouseDown("left")
                    }
                }

                if (movedBeyondSlop) {
                    if (singleFingerScrollMode) {
                        val dyDp = (curY - lastY) / density
                        if (dyDp != 0f) {
                            val sign = if (naturalScroll) 1f else -1f
                            listener?.onScroll(0f, dyDp * sign * 1.35f)
                        }
                    } else {
                        val dxDp = ((curX - lastX) / density) * sensitivity
                        val dyDp = ((curY - lastY) / density) * sensitivity
                        if (dxDp != 0f || dyDp != 0f) {
                            listener?.onMove(dxDp, dyDp)
                        }
                    }
                }
                lastX = curX
                lastY = curY
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerMode == TwoFingerMode.PINCH_ZOOM) {
                    twoFingerDidGesture = true
                    if (currentZoomLevel < 1.08f) {
                        currentZoomLevel = 1.0f
                    }
                    if (lastSentZoom != currentZoomLevel) {
                        lastSentZoom = currentZoomLevel
                        listener?.onZoomChanged(currentZoomLevel)
                    }
                }
                twoFingerMode = TwoFingerMode.NONE
                // Keep ignoring single-finger moves until every finger lifts so cursor never jumps.
                waitForAllUp = true
            }

            MotionEvent.ACTION_UP -> {
                if (twoFingerMode == TwoFingerMode.PINCH_ZOOM) {
                    twoFingerDidGesture = true
                    if (currentZoomLevel < 1.08f) {
                        currentZoomLevel = 1.0f
                    }
                    if (lastSentZoom != currentZoomLevel) {
                        lastSentZoom = currentZoomLevel
                        listener?.onZoomChanged(currentZoomLevel)
                    }
                }

                if (isDragging) {
                    listener?.onMouseUp("left")
                    isDragging = false
                } else if (tapToClickEnabled && !twoFingerDidGesture && maxPointerCount == 1 && !waitForAllUp) {
                    val duration = now - downTime
                    if (!movedBeyondSlop && duration < 240L) {
                        if (singleFingerScrollMode) {
                            handleTikTokTap(now, event.x, event.y)
                        } else {
                            listener?.onClick("left")
                            lastTapUpTime = now
                            lastTapUpX = event.x
                            lastTapUpY = event.y
                        }
                    }
                } else if (
                    multiFingerEnabled &&
                    tapToClickEnabled &&
                    !twoFingerDidGesture &&
                    maxPointerCount == 2 &&
                    twoFingerMode == TwoFingerMode.DECIDING
                ) {
                    val duration = now - multiFingerDownTime
                    val midDeltaDp = hypot(lastMidX - startMidX, lastMidY - startMidY) / density
                    val spanDeltaDp = abs(lastSpanDp - startSpanDp)
                    if (midDeltaDp < 10f && spanDeltaDp < 6f && duration < 240L) {
                        listener?.onClick("right")
                    }
                } else if (multiFingerEnabled && tapToClickEnabled && maxPointerCount == 3) {
                    val duration = now - multiFingerDownTime
                    val midDeltaDp = hypot(lastMidX - startMidX, lastMidY - startMidY) / density
                    if (midDeltaDp < 14f && duration < 270L) {
                        listener?.onClick("middle")
                    }
                }
                resetState()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pendingSingleTapRunnable)
                hasPendingSingleTap = false
                if (isDragging) {
                    listener?.onMouseUp("left")
                    isDragging = false
                }
                resetState()
            }
        }
        return true
    }

    private fun handleTikTokTap(now: Long, x: Float, y: Float) {
        val sinceLastTap = now - lastTapUpTime
        val distDp = hypot(x - lastTapUpX, y - lastTapUpY) / density
        if (hasPendingSingleTap && sinceLastTap in 1..240 && distDp < 38f) {
            // Double-tap in TikTok mode sends a double-click (Like) without pausing first.
            removeCallbacks(pendingSingleTapRunnable)
            hasPendingSingleTap = false
            lastTapUpTime = 0L
            listener?.onClick("left")
            listener?.onClick("left")
        } else {
            removeCallbacks(pendingSingleTapRunnable)
            lastTapUpTime = now
            lastTapUpX = x
            lastTapUpY = y
            hasPendingSingleTap = true
            postDelayed(pendingSingleTapRunnable, TIKTOK_DOUBLE_TAP_WINDOW_MS)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pendingSingleTapRunnable)
        hasPendingSingleTap = false
        super.onDetachedFromWindow()
    }

    private fun spanBetween(event: MotionEvent): Float {
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)) / density
    }

    private fun resetState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        movedBeyondSlop = false
        maxPointerCount = 0
        twoFingerMode = TwoFingerMode.NONE
        twoFingerDidGesture = false
        waitForAllUp = false
        isTapDragCandidate = false
    }

    companion object {
        private const val PINCH_SLOP_DP = 7f
        private const val SCROLL_SLOP_DP = 12f
        private const val TIKTOK_DOUBLE_TAP_WINDOW_MS = 230L
    }
}
