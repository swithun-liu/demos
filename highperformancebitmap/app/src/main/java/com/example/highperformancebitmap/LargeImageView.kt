package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Scroller
import java.io.InputStream

/**
 * 硬核巨图加载 View
 * 核心原理：
 * 1. BitmapRegionDecoder: 只解码屏幕可见区域，避免 OOM。
 * 2. inBitmap: 复用内存块，避免滑动过程中产生内存抖动。
 */
class LargeImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), GestureDetector.OnGestureListener {

    private var mDecoder: BitmapRegionDecoder? = null
    private var mImageWidth = 0
    private var mImageHeight = 0

    // 绘制区域（这也是我们需要解码的区域）
    private val mRect = Rect()

    // 内存复用的关键配置
    private val mOptions = BitmapFactory.Options()

    // 这就是那个被反复复用的 Bitmap 对象
    private var mReuseBitmap: Bitmap? = null

    // 手势处理
    private val mGestureDetector = GestureDetector(context, this)
    private val mScroller = Scroller(context)

    init {
        // 设置图片解码配置，必须设置为 mutable 才能被复用
        mOptions.inPreferredConfig = Bitmap.Config.RGB_565 // 565比ARGB_8888省一半内存
        mOptions.inMutable = true
    }

    /**
     * 外部调用：设置图片流
     */
    fun setInputStream(inputStream: InputStream) {
        try {
            // 1. 初始化区域解码器
            // 注意：在 Android P (9.0) 之后推荐使用 ImageDecoder，但在面试中讲 BitmapRegionDecoder 更显底层
            mDecoder = BitmapRegionDecoder.newInstance(inputStream, false)

            // 2. 获取原图尺寸
            val tmpOptions = BitmapFactory.Options()
            tmpOptions.inJustDecodeBounds = true
            // 这里为了演示简单，并没有真正去读流，因为 RegionDecoder 已经有了宽高信息
            // 实际可以直接从 decoder 获取
            mImageWidth = mDecoder?.width ?: 0
            mImageHeight = mDecoder?.height ?: 0

            requestLayout()
            invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 测量 View 大小，确定加载区域的初始大小
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val viewWidth = measuredWidth
        val viewHeight = measuredHeight

        // 默认显示图片的左上角
        mRect.left = 0
        mRect.top = 0
        mRect.right = viewWidth
        mRect.bottom = viewHeight
    }

    /**
     * 核心绘制方法
     */
    override fun onDraw(canvas: Canvas) {
        val decoder = mDecoder ?: return

        // --- 面试加分项：内存复用开始 ---
        // 如果有现成的 Bitmap 且符合复用条件，就复用它
        mOptions.inBitmap = mReuseBitmap
        // --- 内存复用结束 ---

        try {
            // 解码指定区域 mRect
            // 如果 mOptions.inBitmap 被设置了，decodeRegion 会直接把数据填入 mReuseBitmap，而不会 new 一个新的
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)

            // 绘制 Bitmap
            if (mReuseBitmap != null) {
                // 使用 Matrix 或者是直接 drawBitmap 都可以，这里简单处理
                canvas.drawBitmap(mReuseBitmap!!, 0f, 0f, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- 以下是手势处理逻辑，保证滑动的丝滑 ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return mGestureDetector.onTouchEvent(event)
    }

    override fun onDown(e: MotionEvent): Boolean {
        // 如果正在飞速滑动，按住时停止
        if (!mScroller.isFinished) {
            mScroller.forceFinished(true)
        }
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        // 改变加载区域 mRect 的坐标
        mRect.offset(distanceX.toInt(), distanceY.toInt())
        checkBound() // 边界检查，别滑出去了
        invalidate() // 触发 onDraw 重新解码
        return true
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        // 处理惯性滑动
        mScroller.fling(
            mRect.left, mRect.top,
            -velocityX.toInt(), -velocityY.toInt(),
            0, mImageWidth - width,
            0, mImageHeight - height
        )
        return true
    }

    override fun computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mRect.left = mScroller.currX
            mRect.top = mScroller.currY
            mRect.right = mRect.left + width
            mRect.bottom = mRect.top + height
            checkBound()
            invalidate()
        }
    }

    private fun checkBound() {
        // 简单的边界修正
        if (mRect.left < 0) mRect.left = 0
        if (mRect.top < 0) mRect.top = 0
        if (mRect.right > mImageWidth) mRect.right = mImageWidth
        if (mRect.bottom > mImageHeight) mRect.bottom = mImageHeight

        // 保证宽高不缩放
        mRect.right = mRect.left + width
        mRect.bottom = mRect.top + height
    }

    // 其他不需要实现的手势方法
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onLongPress(e: MotionEvent) {}
}