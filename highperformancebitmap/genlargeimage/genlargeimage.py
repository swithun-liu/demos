from PIL import Image, ImageDraw, ImageFont
import sys
import time

# --- 灭世级参数 ---
# 宽 35000 * 高 30000 = 10.5亿像素
# Android 内存占用预计：3.91 GB (ARGB_8888)
# 任何现存 Android 设备都不应该能一次性硬解这张图。
WIDTH = 35000
HEIGHT = 30000
GRID_SIZE = 1000  # 格子再画大一点，不然电脑要画太久
FILENAME = "big_image.jpg"

# 解除 Pillow 的像素限制
Image.MAX_IMAGE_PIXELS = None

print("="*50)
print(f"警告：即将生成 {WIDTH}x{HEIGHT} 的灭世级巨图！")
print("这会占用你的电脑约 4GB+ 内存进行处理。")
print("如果电脑卡死，请强制结束 Python 进程。")
print("="*50)
time.sleep(2) # 给个后悔的时间

start_time = time.time()

# 1. 创建画布
print(f"[{time.time()-start_time:.1f}s] 正在分配电脑内存...")
try:
    # 这里可能会让电脑卡一下
    img = Image.new('RGB', (WIDTH, HEIGHT), color='white')
except MemoryError:
    print("错误：你的电脑内存不足以生成这张图！")
    sys.exit(1)

draw = ImageDraw.Draw(img)

# 2. 字体设置
try:
    # 字体搞大点
    font = ImageFont.truetype("Arial.ttf", 150) 
except:
    font = None

print(f"[{time.time()-start_time:.1f}s] 开始绘制内容 (请耐心等待)...")

# 3. 绘制内容 (稀疏绘制，节省时间)
total_steps = (WIDTH // GRID_SIZE) * (HEIGHT // GRID_SIZE)
current_step = 0

for x in range(0, WIDTH, GRID_SIZE):
    for y in range(0, HEIGHT, GRID_SIZE):
        current_step += 1
        if current_step % 100 == 0:
             print(f"进度: {current_step / total_steps * 100:.1f}%")

        color = (x % 255, (y // 200) % 255, (x+y) % 255)
        # 画矩形框
        draw.rectangle([x, y, x + GRID_SIZE, y + GRID_SIZE], outline=color, width=5)
        # 写巨大的坐标
        text = f"{x},{y}"
        draw.text((x + 50, y + 50), text, fill="black", font=font)

print(f"[{time.time()-start_time:.1f}s] 绘制完成，正在进行 JPG 压缩保存 (最耗时步骤)...")
print("电脑风扇可能会起飞，请稍候...")

# 4. 保存
# 使用较低质量以减小文件体积，方便传输，不影响解压后的内存占用
img.save(FILENAME, quality=60) 

end_time = time.time()
print("="*50)
print(f"✅ 完成！耗时: {end_time - start_time:.1f} 秒")
print(f"灭世图已生成: {FILENAME}")
print("请将其放入 Android 项目的 assets 目录覆盖原图。")
print("预期结果：点击普通加载后，Toast 提示约 3.9GB，然后瞬间触发 OOM 异常。")
print("="*50)
