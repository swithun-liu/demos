package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.Scroller
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 【手势控制容器】
 * 思想：组合 (Composition)
 * 职责：
 * 1. 内部实例化 CoreLargeImageView 并添加到界面
 * 2. 拦截并处理所有 Touch 事件 (Scroll, Fling, Scale)
 * 3. 维护当前的 Rect 状态，并进行边界修正 (checkBound)
 * 4. 将计算好的 Rect 塞给 CoreLargeImageView 去画
 */
class GestureLargeImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    GestureDetector.OnGestureListener,
    ScaleGestureDetector.OnScaleGestureListener {

    // --- 组合：将渲染核心作为属性 ---
    private val coreView: CoreLargeImageView = CoreLargeImageView(context)

    // 手势相关
    private val mGestureDetector = GestureDetector(context, this)
    private val mScaleGestureDetector = ScaleGestureDetector(context, this)
    private val mScroller = Scroller(context)

    // 逻辑状态
    private val mRect = Rect()
    private var mImageWidth = 0
    private var mImageHeight = 0
    private var mScale = 1.0f

    init {
        // 将核心渲染 View 添加为子 View，填满父容器
        addView(coreView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 对外暴露的设置图片接口
     */
    fun setInputStream(inputStream: InputStream) {
        // 1. 先把流给渲染层初始化
        coreView.setInputStream(inputStream)

        // 2. 从渲染层同步原始尺寸信息 (用于边界检查)
        mImageWidth = coreView.imageWidth
        mImageHeight = coreView.imageHeight

        // 3. 初始化 Rect
        post {
            // 需要等 Layout 也就是 coreView 有尺寸了再初始化
            if (width > 0 && height > 0) {
                mRect.left = 0
                mRect.top = 0
                mRect.right = width
                mRect.bottom = height
                mScale = 1.0f
                updateCore()
            }
        }
    }

    /**
     * 将计算好的 Rect 同步给 CoreView
     */
    private fun updateCore() {
        coreView.setVisibleRect(mRect)
    }

    // --- 事件分发 ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var ret = mScaleGestureDetector.onTouchEvent(event)
        if (!mScaleGestureDetector.isInProgress) {
            ret = mGestureDetector.onTouchEvent(event)
        }
        return ret
    }

    // --- Scale 逻辑 ---
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        mScale *= detector.scaleFactor
        mScale = max(0.1f, min(mScale, 4.0f))

        // 计算新的 Rect 大小：View宽 / Scale
        val newWidth = (width / mScale).toInt()
        val newHeight = (height / mScale).toInt()

        // 中心缩放计算
        val centerX = mRect.centerX()
        val centerY = mRect.centerY()

        mRect.left = centerX - newWidth / 2
        mRect.right = mRect.left + newWidth
        mRect.top = centerY - newHeight / 2
        mRect.bottom = mRect.top + newHeight

        checkBound()
        updateCore()
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    // --- Scroll / Fling 逻辑 ---
    override fun onDown(e: MotionEvent): Boolean {
        if (!mScroller.isFinished) mScroller.forceFinished(true)
        return true
    }

    // 1. 修正滑动：距离需要除以 Scale
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (mScaleGestureDetector.isInProgress) return false

        // 核心修正：屏幕距离 / 缩放比例 = 原图逻辑距离
        // 举例：缩小到 0.5 倍时，mScale=0.5。手指滑 100px，实际应移动 100/0.5 = 200px。
        val moveX = distanceX / mScale
        val moveY = distanceY / mScale

        mRect.offset(moveX.toInt(), moveY.toInt())
        checkBound()
        updateCore()
        return true
    }

    // 2. 修正惯性滑动：速度也需要除以 Scale
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (mScaleGestureDetector.isInProgress) return false

        // 核心修正：物理速度映射
        // 如果不除以 scale，缩小时惯性也会显得特别小，滑一下就停了
        val scaledVx = velocityX / mScale
        val scaledVy = velocityY / mScale

        mScroller.fling(
            mRect.left, mRect.top,
            -scaledVx.toInt(), -scaledVy.toInt(),
            0, mImageWidth - mRect.width(),
            0, mImageHeight - mRect.height()
        )
        invalidate()
        return true
    }

    override fun computeScroll() {
        // 注意：这里需要重写 FrameLayout 的 computeScroll
        if (mScroller.computeScrollOffset()) {
            val currentW = mRect.width()
            val currentH = mRect.height()

            mRect.left = mScroller.currX
            mRect.top = mScroller.currY
            mRect.right = mRect.left + currentW
            mRect.bottom = mRect.top + currentH

            checkBound()
            updateCore()

            // 继续触发下一帧动画，注意这里是调用自己的 invalidate，
            // 从而间接导致系统再次调用 computeScroll
            postInvalidate()
        }
    }

    // --- 边界检查 ---
    private fun checkBound() {
        val imgW = mImageWidth
        val imgH = mImageHeight

        // 宽度修正
        if (mRect.width() > imgW) {
            mRect.left = 0
            mRect.right = imgW
        } else {
            if (mRect.left < 0) mRect.offset(-mRect.left, 0)
            if (mRect.right > imgW) mRect.offset(imgW - mRect.right, 0)
        }

        // 高度修正
        if (mRect.height() > imgH) {
            mRect.top = 0
            mRect.bottom = imgH
        } else {
            if (mRect.top < 0) mRect.offset(0, -mRect.top)
            if (mRect.bottom > imgH) mRect.offset(0, imgH - mRect.bottom)
        }
    }

    // 其他空实现
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onLongPress(e: MotionEvent) {}
}