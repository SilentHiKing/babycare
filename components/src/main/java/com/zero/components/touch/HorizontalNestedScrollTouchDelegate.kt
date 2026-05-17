package com.zero.components.touch

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap
import kotlin.math.abs

object HorizontalNestedScrollTouchDelegate {
    private val attachedListeners = WeakHashMap<RecyclerView, RecyclerView.OnItemTouchListener>()

    fun attachTo(recyclerView: RecyclerView) {
        attachedListeners.remove(recyclerView)?.let { recyclerView.removeOnItemTouchListener(it) }

        val listener = DirectionLockItemTouchListener(
            touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        )
        attachedListeners[recyclerView] = listener
        recyclerView.addOnItemTouchListener(listener)
    }

    private class DirectionLockItemTouchListener(
        private val touchSlop: Int
    ) : RecyclerView.SimpleOnItemTouchListener() {
        private var activePointerId = INVALID_POINTER_ID
        private var downX = 0f
        private var downY = 0f
        private var directionLock = ScrollDirectionLock.UNDECIDED

        override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
            handleTouchEvent(recyclerView, event)
            return false
        }

        override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
            handleTouchEvent(recyclerView, event)
        }

        private fun handleTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    downX = event.x
                    downY = event.y
                    directionLock = ScrollDirectionLock.UNDECIDED
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> handleMove(recyclerView, event)
                MotionEvent.ACTION_POINTER_UP -> handlePointerUp(recyclerView, event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    directionLock = ScrollDirectionLock.UNDECIDED
                    activePointerId = INVALID_POINTER_ID
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        private fun handleMove(recyclerView: RecyclerView, event: MotionEvent) {
            val pointerIndex = event.findPointerIndex(activePointerId)
            if (pointerIndex < 0) return

            val deltaX = event.getX(pointerIndex) - downX
            val deltaY = event.getY(pointerIndex) - downY
            val absDx = abs(deltaX)
            val absDy = abs(deltaY)

            when (directionLock) {
                ScrollDirectionLock.HORIZONTAL -> {
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                    return
                }
                ScrollDirectionLock.VERTICAL -> {
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
                    return
                }
                ScrollDirectionLock.UNDECIDED -> Unit
            }

            if (absDx < touchSlop && absDy < touchSlop) {
                recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                return
            }

            val horizontalIntent = absDx > touchSlop && absDx >= absDy * HORIZONTAL_INTENT_RATIO
            val canScrollHorizontally = recyclerView.canScrollForDrag(deltaX)
            if (horizontalIntent && canScrollHorizontally) {
                // 一旦横向意图成立，就锁定给子 RecyclerView，避免后续 dy 抖动让父级 NestedScrollView 抢走手势。
                directionLock = ScrollDirectionLock.HORIZONTAL
                recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                return
            }

            if (absDy > touchSlop) {
                // 纵向意图明确时释放父级，保证用户从分类区域也能自然滚动整页。
                directionLock = ScrollDirectionLock.VERTICAL
                recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
            } else {
                recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        private fun handlePointerUp(recyclerView: RecyclerView, event: MotionEvent) {
            val liftedPointerId = event.getPointerId(event.actionIndex)
            if (liftedPointerId != activePointerId) return

            val nextPointerIndex = if (event.actionIndex == 0) 1 else 0
            if (nextPointerIndex >= event.pointerCount) return

            activePointerId = event.getPointerId(nextPointerIndex)
            downX = event.getX(nextPointerIndex)
            downY = event.getY(nextPointerIndex)
            directionLock = ScrollDirectionLock.UNDECIDED
            recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun RecyclerView.canScrollForDrag(deltaX: Float): Boolean {
            if (deltaX == 0f) return canScrollHorizontally(-1) || canScrollHorizontally(1)
            val contentDirection = if (deltaX > 0f) -1 else 1
            return canScrollHorizontally(contentDirection)
        }
    }

    private enum class ScrollDirectionLock {
        UNDECIDED,
        HORIZONTAL,
        VERTICAL
    }

    private const val INVALID_POINTER_ID = -1
    private const val HORIZONTAL_INTENT_RATIO = 0.8f
}
