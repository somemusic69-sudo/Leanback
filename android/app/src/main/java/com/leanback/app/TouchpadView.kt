package com.leanback.app

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

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
        fun onTouchInteract()
    }

    var listener: Listener? = null
    var sensitivity: Float = 1.25f
    var naturalScroll: Boolean = true
    var tapToClickEnabled: Boolean = true

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
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var multiFingerDistanceDp = 0f
    private var waitForAllUp = false

    private var isDragging = false
    private var isTapDragCandidate = false
    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
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
                waitForAllUp = false
                isDragging = false

                val sinceLastTap = now - lastTapUpTime
                val distFromLastTapDp = hypot(downX - lastTapUpX, downY - lastTapUpY) / density
                isTapDragCandidate =
                    tapToClickEnabled && sinceLastTap in 1..240 && distFromLastTapDp < 28f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
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
                    multiFingerDistanceDp = 0f
                    lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (event.pointerCount == 2) {
                        val midX = (event.getX(0) + event.getX(1)) * 0.5f
                        val midY = (event.getY(0) + event.getY(1)) * 0.5f
                        val dxDp = (midX - lastMidX) / density
                        val dyDp = (midY - lastMidY) / density
                        lastMidX = midX
                        lastMidY = midY

                        multiFingerDistanceDp += hypot(dxDp, dyDp)
                        if (multiFingerDistanceDp > 6f) {
                            val sign = if (naturalScroll) 1f else -1f
                            listener?.onScroll(dxDp * sign, dyDp * sign)
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
                    if (isTapDragCandidate && !isDragging) {
                        isDragging = true
                        listener?.onMouseDown("left")
                    }
                }

                if (movedBeyondSlop) {
                    val dxDp = ((curX - lastX) / density) * sensitivity
                    val dyDp = ((curY - lastY) / density) * sensitivity
                    if (dxDp != 0f || dyDp != 0f) {
                        listener?.onMove(dxDp, dyDp)
                    }
                }
                lastX = curX
                lastY = curY
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Keep ignoring single-finger moves until every finger lifts so cursor never jumps.
                waitForAllUp = true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    listener?.onMouseUp("left")
                    isDragging = false
                } else if (tapToClickEnabled && maxPointerCount == 1 && !waitForAllUp) {
                    val duration = now - downTime
                    if (!movedBeyondSlop && duration < 240L) {
                        listener?.onClick("left")
                        lastTapUpTime = now
                        lastTapUpX = event.x
                        lastTapUpY = event.y
                    }
                } else if (tapToClickEnabled && maxPointerCount == 2) {
                    val duration = now - multiFingerDownTime
                    if (multiFingerDistanceDp < 10f && duration < 270L) {
                        listener?.onClick("right")
                    }
                } else if (tapToClickEnabled && maxPointerCount == 3) {
                    val duration = now - multiFingerDownTime
                    if (multiFingerDistanceDp < 14f && duration < 290L) {
                        listener?.onClick("middle")
                    }
                }
                resetState()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener?.onMouseUp("left")
                    isDragging = false
                }
                resetState()
            }
        }
        return true
    }

    private fun resetState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        movedBeyondSlop = false
        maxPointerCount = 0
        waitForAllUp = false
        isTapDragCandidate = false
    }
}
