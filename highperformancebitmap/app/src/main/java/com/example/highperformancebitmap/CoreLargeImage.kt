package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
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

    // --- 【新增】调试用画笔 ---
    private val mDebugTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK) // 加阴影防止看不清
    }
    private val mDebugBgPaint = Paint().apply {
        color = Color.parseColor("#80000000") // 半透明黑底
        style = Paint.Style.FILL
    }

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

        // 边界修正
        if (mRect.left < 0) mRect.left = 0
        if (mRect.top < 0) mRect.top = 0
        if (mRect.right > imageWidth) mRect.right = imageWidth
        if (mRect.bottom > imageHeight) mRect.bottom = imageHeight
        if (mRect.width() <= 0 || mRect.height() <= 0) return

        val sampleSize = calculateInSampleSizeV3(mRect.width(), viewWidth)
        mOptions.inSampleSize = sampleSize

        // 计算目标宽度
        val targetWidth = mRect.width() / sampleSize

        // 记录旧宽度用于显示 (如果没有旧图，那它就是即将生成的宽度)
        val containerWidth = mReuseBitmap?.width ?: targetWidth

        if (mReuseBitmap != null) {
            if (enableArtifactsFix) {
                if (abs(mReuseBitmap!!.width - targetWidth) > 5) {
                    mReuseBitmap = null
                }
            }
        }

        mOptions.inBitmap = mReuseBitmap

        try {
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)
            val bitmap = mReuseBitmap

            if (bitmap != null) {
                // 绘制图片
                mMatrix.reset()
                val scaleX = viewWidth.toFloat() / bitmap.width
                val scaleY = viewHeight.toFloat() / bitmap.height
                val scale = kotlin.math.min(scaleX, scaleY)
                val dx = (viewWidth - bitmap.width * scale) / 2
                val dy = (viewHeight - bitmap.height * scale) / 2
                mMatrix.setScale(scale, scale)
                mMatrix.postTranslate(dx, dy)
                canvas.drawBitmap(bitmap, mMatrix, null)

                // --- 【新增】绘制可视化调试面板 ---
                drawDebugHUD(canvas, containerWidth, targetWidth, bitmap.width)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 绘制错误提示
            canvas.drawColor(Color.RED)
            canvas.drawText("CRASHED: ${e.javaClass.simpleName}", 50f, 100f, mDebugTextPaint)
            mReuseBitmap = null
        }
    }

    // --- 【新增】绘制 HUD 面板的方法 ---
    private fun drawDebugHUD(canvas: Canvas, oldWidth: Int, reqWidth: Int, realWidth: Int) {
        val diff = abs(oldWidth - reqWidth)
        val isMismatch = diff > 0 && !enableArtifactsFix

        // 1. 绘制背景条
        canvas.drawRect(0f, 0f, width.toFloat(), 180f, mDebugBgPaint)

        // 2. 状态指示灯
        mDebugTextPaint.color = if (isMismatch) Color.RED else Color.GREEN
        val statusText = if (isMismatch) "⚠ 内存错位 (GLITCHING)" else "✔ 内存对齐 (SAFE)"
        mDebugTextPaint.textSize = 50f
        canvas.drawText(statusText, 30f, 60f, mDebugTextPaint)

        // 3. 详细数据
        mDebugTextPaint.color = Color.WHITE
        mDebugTextPaint.textSize = 35f

        val line1 = "容器宽(Old Bitmap): $oldWidth px"
        val line2 = "内容宽(Request): $reqWidth px"
        val line3 = "差值(Diff): $diff px"

        canvas.drawText(line1, 30f, 110f, mDebugTextPaint)

        // 如果有差值，高亮显示差值
        if (diff > 0) mDebugTextPaint.color = Color.YELLOW
        canvas.drawText("$line2   |   $line3", 30f, 150f, mDebugTextPaint)
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