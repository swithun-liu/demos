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

    // --- 【变化点】现在持有的是带 HUD 的外壳 ---
    private val wrapperView = DebuggableWrapperView(context)

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
        addView(wrapperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // 【新增】设置是否开启修复
    fun setArtifactsFix(enable: Boolean) {
        wrapperView.setArtifactsFix(enable)
    }

    /**
     * 对外暴露的设置图片接口
     */
    fun setInputStream(inputStream: InputStream) {
        // 1. 初始化
        wrapperView.setInputStream(inputStream)
        mImageWidth = wrapperView.imageWidth
        mImageHeight = wrapperView.imageHeight

        // 2. 初始全屏
        post {
            if (width > 0 && height > 0) {
                mRect.left = 0
                mRect.top = 0
                mRect.right = width
                mRect.bottom = height
                mScale = 1.0f
                updateView()
            }
        }
    }

    // 更新画面
    private fun updateView() {
        wrapperView.setVisibleRect(mRect)
    }

    // --- 事件分发 ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var ret = mScaleGestureDetector.onTouchEvent(event)
        if (!mScaleGestureDetector.isInProgress) {
            ret = mGestureDetector.onTouchEvent(event)
        }
        return ret
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val newScale = mScale * detector.scaleFactor
        val minScale = min(width.toFloat() / mImageWidth, height.toFloat() / mImageHeight)

        mScale = newScale
        if (mScale < minScale * 1.05f) mScale = minScale
        mScale = min(mScale, 4.0f)

        if (mScale == minScale) {
            mRect.set(0, 0, mImageWidth, mImageHeight)
        } else {
            val newWidth = (width / mScale).toInt()
            val newHeight = (height / mScale).toInt()
            val centerX = mRect.centerX()
            val centerY = mRect.centerY()

            mRect.left = centerX - newWidth / 2
            mRect.right = mRect.left + newWidth
            mRect.top = centerY - newHeight / 2
            mRect.bottom = mRect.top + newHeight
        }
        checkBound()
        updateView()
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
        val moveX = distanceX / mScale
        val moveY = distanceY / mScale

        val isWidthFit = mRect.width() >= mImageWidth - 5
        val isHeightFit = mRect.height() >= mImageHeight - 5
        val dx = if (isWidthFit) 0 else moveX.toInt()
        val dy = if (isHeightFit) 0 else moveY.toInt()

        if (dx == 0 && dy == 0) return true

        mRect.offset(dx, dy)
        checkBound()
        updateView()
        return true
    }

    // 2. 修正惯性滑动：速度也需要除以 Scale
    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
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
            updateView()

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