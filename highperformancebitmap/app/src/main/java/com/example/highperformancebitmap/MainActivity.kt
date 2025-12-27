package com.example.highperformancebitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.container)

        findViewById<Button>(R.id.btnLoadCrash).setOnClickListener {
            loadTheNormalWay_Crash()
        }

        findViewById<Button>(R.id.btnLoadLarge).setOnClickListener {
            loadTheOptimizedWay()
        }
    }

    /**
     * 方案一：普通加载（反面教材）
     * 1. 先计算理论内存占用，告诉你“这必死无疑”。
     * 2. 然后真的去加载，演示崩溃。
     */
    private fun loadTheNormalWay_Crash() {
        container.removeAllViews()

        // --- 步骤 A: 预判 ---
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        var streamForMeasure: InputStream? = null
        try {
            streamForMeasure = assets.open("big_image.jpg")
            BitmapFactory.decodeStream(streamForMeasure, null, options)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            streamForMeasure?.close()
        }

        val width = options.outWidth
        val height = options.outHeight
        val sizeInMB = (width.toLong() * height.toLong() * 4) / (1024.0 * 1024.0)

        val warningMsg = "尺寸: ${width}x${height} (约 %.2f MB)\n准备进行内存轰炸...".format(sizeInMB)
        Log.d("MainActivity", warningMsg)
        Toast.makeText(this, warningMsg, Toast.LENGTH_SHORT).show()

        container.postDelayed({
            try {
                Log.d("MainActivity", "开始尝试加载...")
                val inputStream: InputStream = assets.open("big_image.jpg")

                // 1. 尝试正常解码
                var bitmap = BitmapFactory.decodeStream(inputStream)

                // 2. 关键补刀：如果解码器怂了（返回null），我们就手动制造惨案
                if (bitmap == null) {
                    Log.e("MainActivity", "系统解码器拒绝加载巨图 (返回了 null)。")
                    Log.e("MainActivity", "正在绕过解码器，强制申请内存...")

                    Toast.makeText(this, "解码器拒绝加载，正在手动触发 OOM...", Toast.LENGTH_SHORT).show()

                    // 【必杀技】直接申请 35000x30000 的空白位图
                    // 这行代码等同于加载了原图的内存大小，且无法被系统优化
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }

                val imageView = ImageView(this)
                imageView.setImageBitmap(bitmap)
                container.addView(imageView)

                Log.d("MainActivity", "竟然真的没崩？这台手机是神机吗？")

            } catch (e: OutOfMemoryError) {
                // --- 预期结果 ---
                e.printStackTrace()
                Log.e("MainActivity", "OOM 捕获成功！内存爆了！")

                val crashMsg = "OOM 成功复现！\n分配 %.2f MB 失败。".format(sizeInMB)
                Toast.makeText(this, crashMsg, Toast.LENGTH_LONG).show()

                val errorText = android.widget.TextView(this)
                errorText.text = "OOM CRASH !!!\n(内存溢出)"
                errorText.textSize = 30f
                errorText.setTextColor(android.graphics.Color.RED)
                errorText.gravity = android.view.Gravity.CENTER
                container.addView(errorText)

            } catch (e: Exception) {
                // 捕获可能出现的 Canvas 绘制异常（如果在申请内存时没崩，绘制时也会崩）
                Log.e("MainActivity", "其他异常: ${e.message}")
                e.printStackTrace()
            }
        }, 1000)
    }
    /**
     * 方案二：优化加载（正确答案）
     * 使用 BitmapRegionDecoder 和 内存复用
     */
    private fun loadTheOptimizedWay() {
        container.removeAllViews()

        try {
            val largeImageView = LargeImageView(this)
            container.addView(largeImageView)

            val inputStream: InputStream = assets.open("big_image.jpg")
            largeImageView.setInputStream(inputStream)

            Toast.makeText(this, "加载成功，内存占用极低", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}