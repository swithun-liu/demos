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
import kotlin.math.abs

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

        // 1. 计算采样率
        mOptions.inSampleSize = calculateInSampleSize(mRect.width(), viewWidth)

        // 2. 【核心修复】智能内存复用策略
        // 计算当前这帧解码“理论上”会生成的 Bitmap 宽度
        val targetWidth = mRect.width() / mOptions.inSampleSize

        // 检查现有 Bitmap 是否适合复用
        if (mReuseBitmap != null) {
            // 如果现有 Bitmap 的宽度和我们预期的宽度差异较大（说明正在缩放，尺寸在剧烈变化）
            // 这种情况下强制复用会导致底层 Stride 对齐错误，产生花屏。
            // 策略：放弃复用，置空让系统重新分配。
            if (abs(mReuseBitmap!!.width - targetWidth) > 5) { // 允许 5 像素以内的误差（处理整除精度问题）
                // 放弃旧图，避免花屏
                mReuseBitmap = null
            }
        }

        mOptions.inBitmap = mReuseBitmap

        try {
            // 3. 解码
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)

            val bitmap = mReuseBitmap
            if (bitmap != null) {
                // 4. 矩阵变换
                mMatrix.reset()
                // 注意：这里必须用 bitmap.width，不能用 targetWidth，以解码器实际输出为准
                val scaleX = viewWidth.toFloat() / bitmap.width
                val scaleY = viewHeight.toFloat() / bitmap.height
                mMatrix.setScale(scaleX, scaleY)

                canvas.drawBitmap(bitmap, mMatrix, null)
            }
        } catch (e: IllegalArgumentException) {
            // 复用失败（可能是尺寸缩太小了复用不了），置空重试
            mReuseBitmap = null
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
}