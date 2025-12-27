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
 * 【渲染核心层】 CoreLargeImageView
 *
 * 职责：只负责“怎么画”。
 * 它不关心手势（滑动/缩放），只接收一个 Rect 指令，然后把这个区域的图片解码并画出来。
 * 核心技术栈：BitmapRegionDecoder (局部解码) + inBitmap (内存复用) + Matrix (画面适配)
 */
class CoreLargeImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 区域解码器，它是加载巨图的核心对象，避免一次性将整张大图读入内存
    private var mDecoder: BitmapRegionDecoder? = null

    // 图片的原始宽（例如 35000px）
    var imageWidth = 0
        private set
    // 图片的原始高（例如 30000px）
    var imageHeight = 0
        private set

    // 当前需要绘制的区域 (在原图上的坐标)，由外部手势控制层传入
    private val mRect = Rect()

    // Bitmap 解码配置参数，用于设置采样率、颜色模式、内存复用等
    private val mOptions = BitmapFactory.Options()

    // 这就是那个被反复利用的“内存块”。
    // 我们不会每次 onDraw 都 new Bitmap，而是让解码器把数据填入这个已有的 Bitmap 中。
    private var mReuseBitmap: Bitmap? = null

    // 用于控制 Bitmap 绘制到屏幕时的缩放和平移（实现 Fit Center 效果）
    private val mMatrix = Matrix()

    init {
        // 配置 1: 使用 RGB_565 颜色模式。
        // 相比 ARGB_8888 (4字节/像素)，565 只占 2字节/像素，内存占用直接减半。
        // 对于不含透明度的照片类图片，肉眼几乎看不出区别。
        mOptions.inPreferredConfig = Bitmap.Config.RGB_565

        // 配置 2: 开启可变属性。
        // 只有设置为 true，生成的 Bitmap 才能被后续的解码操作复用 (inBitmap)。
        mOptions.inMutable = true
    }

    /**
     * 设置图片输入流（入口方法）
     * @param inputStream 图片文件的流
     */
    fun setInputStream(inputStream: InputStream) {
        try {
            // 1. 初始化区域解码器
            // newInstance 会读取文件头信息，建立索引，但不会加载像素数据，所以速度很快。
            // shareable = false 表示不共享输入流
            mDecoder = BitmapRegionDecoder.newInstance(inputStream, false)

            // 2. 获取原图尺寸信息
            // 这里的 Options 只用于读取宽高，inJustDecodeBounds = true 表示只读头不读数据
            val tmpOptions = BitmapFactory.Options()
            tmpOptions.inJustDecodeBounds = true

            // 其实有了 decoder，我们可以直接拿 decoder.width / height，不需要再 decode 一次流
            // 这里为了稳健演示，保留了标准写法。实际项目中可以直接用 mDecoder?.width
            imageWidth = mDecoder?.width ?: 0
            imageHeight = mDecoder?.height ?: 0

            // 3. 触发一次布局和重绘，让 View 知道有内容了
            requestLayout()
            invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 外部手势层调用此方法，告诉核心层：“现在我要看原图的哪一块区域”
     * @param rect 原图上的坐标区域 (例如 Left=1000, Top=2000, Right=2080, Bottom=3920)
     */
    fun setVisibleRect(rect: Rect) {
        // 保存当前视口区域
        this.mRect.set(rect)
        // 触发 onDraw，执行解码和绘制
        invalidate()
    }

    /**
     * 【绘制核心流程】
     * 每一帧刷新都会调用这里（特别是在滑动时）。
     * 必须保证这里的逻辑尽可能快，并且没有内存分配（GC），否则会卡顿。
     */
    override fun onDraw(canvas: Canvas) {
        // 如果解码器还没初始化，或者 View 宽高还没测量出来，就不画
        val decoder = mDecoder ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0) return

        // --- 步骤 1: 计算采样率 (inSampleSize) ---
        // 这是性能优化的关键点。我们使用 V3 极速版算法。
        // 如果 mRect 很大（比如缩小看全图时），必须进行降采样，否则 IO 耗时会过长导致卡顿。
        val sampleSize = calculateInSampleSizeV3(mRect.width(), viewWidth)
        mOptions.inSampleSize = sampleSize

        // --- 步骤 2: 内存复用 (inBitmap) 及其保护逻辑 ---

        // 计算当前采样率下，理论上解码出来的 Bitmap 宽度
        // 例如：mRect宽 35000, sampleSize=32 -> targetWidth ≈ 1093
        val targetWidth = mRect.width() / sampleSize

        // 检查现有的复用对象 mReuseBitmap 是否可用
        if (mReuseBitmap != null) {
            // 【防花屏保护】
            // 如果处于缩放过程中，mRect 的宽度会剧烈变化。
            // 比如上一帧宽 1000，这一帧宽 1005。如果强行复用，会导致底层像素行对齐 (Stride) 错误，
            // 表现为画面错位、花屏。
            // 策略：如果尺寸差异超过 5 像素，认为 Bitmap 不匹配，放弃复用，置空让系统重新分配。
            if (abs(mReuseBitmap!!.width - targetWidth) > 5) {
                mReuseBitmap = null
            }
        }

        // 将可用的（或空的）Bitmap 填入 Options，告诉解码器：“请把数据填到这里面，别申请新内存”
        mOptions.inBitmap = mReuseBitmap

        try {
            // --- 步骤 3: 执行解码 (耗时操作) ---
            // 这是一个同步 IO 操作。解码器读取 mRect 区域的数据，根据 sampleSize 缩放，填入 mReuseBitmap。
            // 优化目标：通过 V3 算法，控制这里的耗时在 20ms 以内。
            // 注意：measureTimeMillis 只是为了测试，生产环境可以去掉。
            mReuseBitmap = decoder.decodeRegion(mRect, mOptions)

            // 获取解码结果 (注意：mReuseBitmap 在这里被赋值/填充了数据)
            val bitmap = mReuseBitmap

            // 如果解码成功
            if (bitmap != null) {
                // --- 步骤 4: 矩阵变换 (FIT_CENTER) ---
                // 解码出来的 Bitmap 尺寸通常不等于 View 尺寸 (因为 sampleSize 只能是 2 的倍数)。
                // 我们需要用 Matrix 把它缩放并居中显示。
                mMatrix.reset()

                // 4.1 分别计算宽度和高度需要的缩放比例
                // 比如 View宽 1080, Bitmap宽 540 -> scaleX = 2.0 (放大)
                val scaleX = viewWidth.toFloat() / bitmap.width
                val scaleY = viewHeight.toFloat() / bitmap.height

                // 4.2 取较小值作为最终缩放比。
                // 这就是 FIT_CENTER 的核心：保证图片长边完全展示在屏幕内，短边留白，不变形。
                var scale = kotlin.math.min(scaleX, scaleY)

                // 4.3 计算居中偏移量 (Center Inside)
                // 原理：(容器宽 - 图片实际显示宽) / 2 = 左边留白的距离
                val dx = (viewWidth - bitmap.width * scale) / 2
                val dy = (viewHeight - bitmap.height * scale) / 2

                // 应用缩放和平移
                mMatrix.setScale(scale, scale)
                mMatrix.postTranslate(dx, dy)

                // --- 步骤 5: 绘制到画布 ---
                // 参数：源 Bitmap, 变换矩阵, 画笔(null)
                canvas.drawBitmap(bitmap, mMatrix, null)
            }
        } catch (e: IllegalArgumentException) {
            // 复用失败捕获：有时候 inBitmap 的条件极其严苛（比如新图比旧图大 1 个字节），会抛异常。
            // 补救措施：直接丢弃旧图，下一次 onDraw 会重新分配。
            mReuseBitmap = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // (V1 版算法 - 已弃用) 官方算法，太保守，导致解码图过大
    private fun calculateInSampleSize(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1
        if (reqWidth > viewWidth) {
            val halfWidth = reqWidth / 2
            while ((halfWidth / inSampleSize) >= viewWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // (V2 版算法 - 已弃用) 激进算法，试图让解码图贴合 View 宽，但忽略了巨图下的 IO 瓶颈
    private fun calculateInSampleSizeV2(reqWidth: Int, viewWidth: Int): Int {
        var inSampleSize = 1
        if (reqWidth > viewWidth) {
            while ((reqWidth / (inSampleSize * 2)) >= viewWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 【V3 极速流模式 - 当前使用版本】
     * 策略：
     * 1. 正常情况：保持解码尺寸 >= 屏幕尺寸，保证清晰度。
     * 2. 极端情况：如果原图区域实在太大 (超过 threshold 阈值)，此时 IO 读取是主要瓶颈。
     * 我们宁愿牺牲清晰度（让 sampleSize 再翻倍），也要保证滑动流畅度。
     */
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
}