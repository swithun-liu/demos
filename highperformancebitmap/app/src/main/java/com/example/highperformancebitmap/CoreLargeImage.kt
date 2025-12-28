package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.io.InputStream
import kotlin.math.abs

class CoreLargeImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mDecoder: BitmapRegionDecoder? = null
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set

    // 【新增】开关：是否开启防花屏/防崩溃保护
    var enableArtifactsFix: Boolean = true

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

        // --- 1. 强行修正边界 (防止崩溃) ---
        // 这一步如果不加，关闭修复开关时可能会直接 Crash 而不是花屏，
        // 为了让你能看到花屏效果，我们保留边界修正，只控制内存复用的逻辑。
        if (mRect.left < 0) mRect.left = 0
        if (mRect.top < 0) mRect.top = 0
        if (mRect.right > imageWidth) mRect.right = imageWidth
        if (mRect.bottom > imageHeight) mRect.bottom = imageHeight
        if (mRect.width() <= 0 || mRect.height() <= 0) return

        val sampleSize = calculateInSampleSizeV3(mRect.width(), viewWidth)
        mOptions.inSampleSize = sampleSize

        // --- 2. 内存复用逻辑 (开关控制点) ---
        val targetWidth = mRect.width() / sampleSize

        if (mReuseBitmap != null) {
            // 【关键修改】这里加上了开关判断
            // 如果开启修复(true)：检查尺寸差异，差异大则置空。
            // 如果关闭修复(false)：不管尺寸差多少，强行复用！(这就会导致花屏)
            if (enableArtifactsFix) {
                if (abs(mReuseBitmap!!.width - targetWidth) > 5) {
                    mReuseBitmap = null // 放弃旧图，避免花屏
                }
            } else {
                // 关闭修复模式：什么都不做，让它强行往下走
            }
        }

        mOptions.inBitmap = mReuseBitmap

        try {
            // 解码
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)

            val bitmap = mReuseBitmap
            if (bitmap != null) {
                mMatrix.reset()
                val scaleX = viewWidth.toFloat() / bitmap.width
                val scaleY = viewHeight.toFloat() / bitmap.height
                val scale = kotlin.math.min(scaleX, scaleY)
                val dx = (viewWidth - bitmap.width * scale) / 2
                val dy = (viewHeight - bitmap.height * scale) / 2

                mMatrix.setScale(scale, scale)
                mMatrix.postTranslate(dx, dy)
                canvas.drawBitmap(bitmap, mMatrix, null)
            }
        } catch (e: IllegalArgumentException) {
            // 如果关闭了修复，这里极有可能抛出异常 (因为复用条件不仅是 Stride，还有字节数大小)
            // 为了不 Crash 导致你看不到现象，我们 Catch 住并打印日志
            Log.e(TAG, "复用失败(系统底层拒绝): ${e.message}")
            // 如果系统强制拒绝复用，我们只能置空，否则下一次还会崩
            mReuseBitmap = null
        } catch (e: Exception) {
            e.printStackTrace()
            mReuseBitmap = null
        }
    }

    private fun calculateInSampleSizeV3(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1

        // 第一层：基础计算（同 V2），目标是不比屏幕大太多
        if (reqWidth > viewWidth) {
            // 循环判断：只要下一次压缩后的尺寸依然 >= 屏幕尺寸，就继续压缩
            while ((reqWidth / (inSampleSize * 2)) >= viewWidth) {
                inSampleSize *= 2
            }
        }

        // 第二层：性能兜底（V3 核心）
        // 计算当前 mRect 包含的原始像素数量 (宽 * 高)
        // 注意转成 Long 防止溢出
        val rawRegionSize = reqWidth.toLong() * mRect.height()

        // 设定一个 IO 舒适区阈值。
        // 经验值：2000*2000 像素的数据量是主线程能承受的 IO 极限。
        // 超过这个值，decodeRegion 的耗时会显著上升到 100ms+。
        val threshold = 2000 * 2000

        // 估算一下：在当前 inSampleSize 下，我们还要读取多少数据？
        // 其实准确的评估应该是 rawRegionSize，但这里用除以 sample 的平方粗略估算解码压力
        // 如果原始数据量实在太大，我们强行再降一档采样率 (inSampleSize * 2)
        if (rawRegionSize / (inSampleSize * inSampleSize) > threshold) {
            inSampleSize *= 2
        }

        return inSampleSize
    }

    companion object {
        private const val TAG = "CoreLargeImage"
    }
}