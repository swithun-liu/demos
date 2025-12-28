package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.view.View
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * 【最内层：渲染引擎】
 * 职责：只做一件事——给一个 Rect，把它画出来。
 * 特点：没有调试代码，没有画笔，只有核心的性能逻辑。
 */
class CoreLogicView(context: Context) : View(context) {

    // --- 核心组件 ---
    private var mDecoder: BitmapRegionDecoder? = null
    private val mOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565 // 1. 节省内存 (2字节/像素)
        inMutable = true                          // 2. 开启复用 (关键)
    }

    // 复用池：这是唯一的 Bitmap 对象，内存地址即便在刷新时也不变
    private var mReuseBitmap: Bitmap? = null

    // 渲染辅助
    private val mRect = Rect()
    private val mMatrix = Matrix()

    // --- 对外暴露的属性 ---
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set

    // 开关：由外层 Wrapper 控制
    var enableArtifactsFix = true

    // 数据传输：把内部的解码状态传给外层 HUD 显示
    val debugInfo = DebugInfo()

    fun setInputStream(ips: InputStream) {
        // 初始化解码器，读取文件头
        mDecoder = BitmapRegionDecoder.newInstance(ips, false)
        imageWidth = mDecoder?.width ?: 0
        imageHeight = mDecoder?.height ?: 0
        requestLayout()
    }

    fun setVisibleRect(rect: Rect) {
        // 保存当前要看的区域
        mRect.set(rect)
        // 触发 onDraw
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val decoder = mDecoder ?: return
        if (width == 0 || height == 0) return

        // 1. 边界修正 (防止 Crash)
        // 必须保证 Rect 在原图范围内，否则 decodeRegion 会抛异常
        if (mRect.left < 0) mRect.left = 0
        if (mRect.top < 0) mRect.top = 0
        if (mRect.right > imageWidth) mRect.right = imageWidth
        if (mRect.bottom > imageHeight) mRect.bottom = imageHeight
        if (mRect.width() <= 0 || mRect.height() <= 0) return

        // 2. 计算采样率 (使用 V3 极速算法)
        // 目的：让解码耗时控制在 16ms 以内
        val sampleSize = calculateFastSampleSize(mRect.width(), width)
        mOptions.inSampleSize = sampleSize

        // 3. 内存复用判定 (防花屏核心)
        val targetWidth = mRect.width() / sampleSize // 这次预期的宽度

        // 记录数据给外层 HUD 用
        debugInfo.containerWidth = mReuseBitmap?.width ?: 0
        debugInfo.requestWidth = targetWidth

        if (mReuseBitmap != null) {
            // 如果开启了修复开关，且尺寸差异大，则放弃复用
            if (enableArtifactsFix && abs(mReuseBitmap!!.width - targetWidth) > 5) {
                mReuseBitmap = null
            }
        }

        // 填入复用池
        mOptions.inBitmap = mReuseBitmap

        try {
            // 4. 执行解码 (同步 IO)
            val startTime = System.currentTimeMillis()
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)
            debugInfo.decodeTime = System.currentTimeMillis() - startTime

            val bitmap = mReuseBitmap ?: return

            // 5. 矩阵变换 (Fit Center)
            mMatrix.reset()
            // 计算缩放比：取宽缩放和高缩放的较小值，保证能完整展示
            val scale = min(
                width.toFloat() / bitmap.width,
                height.toFloat() / bitmap.height
            )
            // 计算居中偏移
            val dx = (width - bitmap.width * scale) / 2
            val dy = (height - bitmap.height * scale) / 2

            mMatrix.setScale(scale, scale)
            mMatrix.postTranslate(dx, dy)

            // 6. 绘制
            canvas.drawBitmap(bitmap, mMatrix, null)

        } catch (e: Exception) {
            // 复用失败兜底：置空，下次重试
            mReuseBitmap = null
            e.printStackTrace()
        }
    }

    // V3 极速采样算法
    private fun calculateFastSampleSize(reqWidth: Int, viewWidth: Int): Int {
        var sample = 1
        // 基础降采样
        if (reqWidth > viewWidth) {
            while ((reqWidth / (sample * 2)) >= viewWidth) {
                sample *= 2
            }
        }
        // IO 瓶颈保护
        val pixelCount = reqWidth.toLong() * mRect.height()
        if (pixelCount / (sample * sample) > 2000 * 2000) {
            sample *= 2
        }
        return sample
    }

    // 用于传递数据的简单类
    class DebugInfo {
        var containerWidth = 0 // 旧 Bitmap 宽度
        var requestWidth = 0   // 新解码需要的宽度
        var decodeTime = 0L    // 解码耗时
    }
}
