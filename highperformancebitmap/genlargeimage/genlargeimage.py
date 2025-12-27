from PIL import Image, ImageDraw, ImageFont
import sys
import time

# --- 灭世级参数 ---
# 宽 35000 * 高 30000 = 10.5亿像素
# 预期生成一个全彩色的巨大马赛克图
WIDTH = 35000
HEIGHT = 30000
GRID_SIZE = 1000  # 格子大小
FILENAME = "big_image.jpg" # 【已修改】直接叫这个名字，不用改 Android 代码

# 解除 Pillow 的像素限制
Image.MAX_IMAGE_PIXELS = None

print("="*50)
print(f"警告：即将生成 {WIDTH}x{HEIGHT} 的【全彩】灭世级巨图！")
print("这次不仅有线，整个背景都会被填满颜色。")
print(f"保存文件名: {FILENAME}")
print("="*50)
time.sleep(1)

start_time = time.time()

# 1. 创建画布
print(f"[{time.time()-start_time:.1f}s] 正在分配电脑内存...")
try:
    img = Image.new('RGB', (WIDTH, HEIGHT), color='white')
except MemoryError:
    print("错误：你的电脑内存不足以生成这张图！")
    sys.exit(1)

draw = ImageDraw.Draw(img)

# 2. 字体设置
try:
    # 尝试加载字体，如果没有就用默认的
    font = ImageFont.truetype("Arial.ttf", 120) 
except:
    font = None

print(f"[{time.time()-start_time:.1f}s] 开始绘制全彩内容 (请耐心等待)...")

# 3. 绘制内容
total_steps = (WIDTH // GRID_SIZE + 1) * (HEIGHT // GRID_SIZE + 1)
current_step = 0

for x in range(0, WIDTH, GRID_SIZE):
    for y in range(0, HEIGHT, GRID_SIZE):
        current_step += 1
        if current_step % 100 == 0:
             print(f"进度: {current_step / total_steps * 100:.1f}%")

        # 生成基于坐标的颜色
        r = x % 255
        g = (y // 100) % 255
        b = (x + y) % 255
        color = (r, g, b)

        # 【关键】fill=color 填充实心颜色
        draw.rectangle(
            [x, y, x + GRID_SIZE, y + GRID_SIZE],
            fill=color,    
            outline=color  
        )

        # 写坐标 (带简易描边，防止看不清)
        if font:
            text = f"{x},{y}"
            offset = 2
            # 伪造白色描边
            draw.text((x + 50 + offset, y + 50 + offset), text, fill="white", font=font)
            draw.text((x + 50 - offset, y + 50 - offset), text, fill="white", font=font)
            # 黑色主体
            draw.text((x + 50, y + 50), text, fill="black", font=font)

print(f"[{time.time()-start_time:.1f}s] 绘制完成，正在进行 JPG 压缩保存...")

# 4. 保存
img.save(FILENAME, quality=60) 

end_time = time.time()
print("="*50)
print(f"✅ 完成！耗时: {end_time - start_time:.1f} 秒")
print(f"文件已生成: {FILENAME}")
print("直接覆盖到 Android 项目的 assets 目录即可。")
print("="*50)
