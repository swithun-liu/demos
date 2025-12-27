package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.io.InputStream

/**
 * 【渲染核心】（已修复缩放花屏问题）
 */
class CoreLargeImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mDecoder: BitmapRegionDecoder? = null

    var imageWidth = 0
        private set
    var imageHeight = 0
        private set

    private val mRect = Rect()
    private val mOptions = BitmapFactory.Options()
    private var mReuseBitmap: Bitmap? = null
    private val mMatrix = Matrix()

    init {
        mOptions.inPreferredConfig = Bitmap.Config.RGB_565
        mOptions.inMutable = true
    }

    fun setInputStream(inputStream: InputStream) {
        try {
            mDecoder = BitmapRegionDecoder.newInstance(inputStream, false)
            val tmpOptions = BitmapFactory.Options()
            tmpOptions.inJustDecodeBounds = true
            imageWidth = mDecoder?.width ?: 0
            imageHeight = mDecoder?.height ?: 0

            requestLayout()
            invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setVisibleRect(rect: Rect) {
        this.mRect.set(rect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val decoder = mDecoder ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0) return

        // 1. 计算采样率 (使用 V3 极速版)
        val sampleSize = calculateInSampleSizeV3(mRect.width(), viewWidth)
        mOptions.inSampleSize = sampleSize

        // 2. 内存复用逻辑 (保持 V2 的防花屏逻辑)
        val targetWidth = mRect.width() / sampleSize
        if (mReuseBitmap != null) {
            // 允许少量误差，防止因整除导致的频繁重新分配
            if (kotlin.math.abs(mReuseBitmap!!.width - targetWidth) > 5) {
                mReuseBitmap = null
            }
        }
        mOptions.inBitmap = mReuseBitmap

        try {
            val decodeTime = kotlin.system.measureTimeMillis {
                mReuseBitmap = decoder.decodeRegion(mRect, mOptions)
            }

            val bitmap = mReuseBitmap
            if (bitmap != null) {
                // --- 3. 【核心修复】Matrix 保持比例居中 (FIT_CENTER) ---
                mMatrix.reset()

                // 3.1 分别计算宽高的缩放比例
                val scaleX = viewWidth.toFloat() / bitmap.width
                val scaleY = viewHeight.toFloat() / bitmap.height

                // 3.2 取较小值，保证图片能全部塞进屏幕，且不变形
                val scale = kotlin.math.min(scaleX, scaleY)

                // 3.3 计算居中偏移量
                // (屏幕宽 - 图片缩放后的宽) / 2 = X轴偏移量
                val dx = (viewWidth - bitmap.width * scale) / 2
                val dy = (viewHeight - bitmap.height * scale) / 2

                mMatrix.setScale(scale, scale)
                mMatrix.postTranslate(dx, dy)

                // 3.4 绘制背景 (可选)
                // 因为是 FitCenter，周围可能会有留白。如果背景不是黑/白，可以在这里画个底色
                // canvas.drawColor(Color.BLACK)

                canvas.drawBitmap(bitmap, mMatrix, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateInSampleSize(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1
        // 只有当解码区域比视图大很多时才降采样
        if (reqWidth > viewWidth) {
            val halfWidth = reqWidth / 2
            while ((halfWidth / inSampleSize) >= viewWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 【性能优化版】计算采样率
     * 之前的算法太保守，导致解码出的图片依然远大于屏幕，造成缩小后滑动卡顿。
     * 这个版本更激进，力求解码尺寸最接近 View 尺寸。
     */
    private fun calculateInSampleSizeV2(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1

        // 只有当原始区域比 View 大的时候才需要压缩
        if (reqWidth > viewWidth) {
            // 原始算法使用 halfWidth (reqWidth / 2)，这会导致 sampleSize 偏小，解码图偏大
            // 例如：Rect=35000, View=1000
            // 官方算法算出 sample=8 -> 解码出 4375px 宽 (是屏幕的4倍！浪费极大性能)

            // --- 激进算法 ---
            // 直接循环判断：只要下一次压缩后的尺寸依然 >= 屏幕尺寸，就继续压缩
            while ((reqWidth / (inSampleSize * 2)) >= viewWidth) {
                inSampleSize *= 2
            }
            // 激进算法算出 sample=32 -> 解码出 1093px 宽 (完美贴合屏幕，性能提升4倍)
        }
        return inSampleSize
    }

    /**
     * 【V3 极速流模式】
     * 策略：
     * 1. 正常情况：保持解码尺寸 >= 屏幕尺寸（保证清晰）。
     * 2. 极端巨图：如果计算出的 sampleSize 导致解码耗时过长（IO瓶颈），
     * 我们宁愿让 sampleSize 再大一点，牺牲清晰度，换取 60fps 流畅度。
     */
    private fun calculateInSampleSizeV3(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1

        // 基础计算：至少不比屏幕大太多
        if (reqWidth > viewWidth) {
            while ((reqWidth / (inSampleSize * 2)) >= viewWidth) {
                inSampleSize *= 2
            }
        }

        // --- V3 新增：性能兜底 ---
        // 如果原始区域大得离谱（比如 > 4000px），说明我们在看很大的图。
        // 此时 IO 是瓶颈。我们强行再降一档采样率！
        // 这里的阈值 4000 是经验值，对应你的 35000px 巨图，大概缩放到 1/8 左右
        val rawRegionSize = reqWidth.toLong() * mRect.height()
        // 假设 2000*2000 的区域是主线程 IO 的舒适区上限
        val threshold = 2000 * 2000

        // 如果当前要解压的原始像素量太大，就在 V2 的基础上再除以 2
        // 这会让图片变模糊，但速度提升 4 倍
        if (rawRegionSize / (inSampleSize * inSampleSize) > threshold) {
            inSampleSize *= 2
        }

        return inSampleSize
    }
}