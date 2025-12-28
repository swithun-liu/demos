package com.example.highperformancebitmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.FrameLayout
import java.io.InputStream
import kotlin.math.abs

/**
 * 【中间层：调试外壳】
 * 职责：
 * 1. 持有并管理 CoreLogicView。
 * 2. 绘制 HUD 调试面板 (画笔、文字都在这里)。
 * 3. 拦截 setArtifactsFix 开关并传给 Core。
 */
class DebuggableWrapperView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // --- 组合：持有核心引擎 ---
    val coreView = CoreLogicView(context)

    // --- 调试画笔资源 ---
    private val mTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val mBgPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 半透明背景
    }

    init {
        // 把核心引擎添加为子 View，填满布局
        addView(coreView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 让容器也能触发绘制 (默认 ViewGroup 不走 onDraw)
        setWillNotDraw(false)
    }

    // --- 代理方法：透传给 Core ---

    fun setInputStream(ips: InputStream) {
        coreView.setInputStream(ips)
    }

    fun setVisibleRect(rect: Rect) {
        coreView.setVisibleRect(rect)
        // Core 刷新时，外壳也要刷新以便重绘 HUD
        invalidate()
    }

    // 对外暴露原始尺寸
    val imageWidth: Int get() = coreView.imageWidth
    val imageHeight: Int get() = coreView.imageHeight

    // 对外暴露修复开关
    fun setArtifactsFix(enable: Boolean) {
        coreView.enableArtifactsFix = enable
    }

    /**
     * 【核心绘制拦截】
     * dispatchDraw 会先调用 super 把子 View (CoreView) 画好，
     * 然后我们再在上面画 HUD。
     */
    override fun dispatchDraw(canvas: Canvas) {
        // 1. 先画核心内容 (CoreLogicView)
        super.dispatchDraw(canvas)

        // 2. 获取核心层的数据
        val info = coreView.debugInfo
        val diff = abs(info.containerWidth - info.requestWidth)
        // 只有当有差异，且关闭了修复开关时，才算“故障”
        val isGlitch = diff > 0 && !coreView.enableArtifactsFix

        // 3. 绘制 HUD 面板
        // 背景
        canvas.drawRect(0f, 0f, width.toFloat(), 220f, mBgPaint)

        // 状态标题
        mTextPaint.color = if (isGlitch) Color.RED else Color.GREEN
        mTextPaint.textSize = 50f
        val status = if (isGlitch) "⚠ 内存错位 (花屏中)" else "✔ 内存安全"
        canvas.drawText(status, 30f, 60f, mTextPaint)

        // 详细参数
        mTextPaint.color = Color.WHITE
        mTextPaint.textSize = 35f
        canvas.drawText("旧容器宽(Stride): ${info.containerWidth} px", 30f, 110f, mTextPaint)

        // 如果有差异，高亮显示
        if (diff > 0) mTextPaint.color = Color.YELLOW
        canvas.drawText("新内容宽(Request): ${info.requestWidth} px", 30f, 150f, mTextPaint)
        canvas.drawText("差值(Diff): $diff px", 30f, 190f, mTextPaint)

        // 耗时
        mTextPaint.color = Color.CYAN
        canvas.drawText("解码耗时: ${info.decodeTime} ms", width - 350f, 60f, mTextPaint)
    }
}