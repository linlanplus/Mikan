package com.onebitmonochrome.blacksbbox.view.home

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Takes over the touch stream once a drag has started, so the finger keeps moving the dragged icon
 * even after it leaves the list it started in (e.g. out of an open folder).
 */
class DragLayer @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun isDragging(): Boolean
        fun onDragMove(rawX: Float, rawY: Float)
        fun onDragEnd(rawX: Float, rawY: Float, cancelled: Boolean)
    }

    var listener: Listener? = null

    /** Last finger position seen by this layer, in screen coordinates. */
    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (listener?.isDragging() == true) {
            // The event that is taken over is not passed to onTouchEvent, so handle it here; it may
            // already be the finger lifting.
            handle(ev)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (listener?.isDragging() != true) return super.onTouchEvent(event)
        handle(event)
        return true
    }

    private fun handle(event: MotionEvent) {
        val l = listener ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> l.onDragMove(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> l.onDragEnd(event.rawX, event.rawY, false)
            MotionEvent.ACTION_CANCEL -> l.onDragEnd(event.rawX, event.rawY, true)
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A child list must not keep the drag from reaching this layer.
        if (listener?.isDragging() == true) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
