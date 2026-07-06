# Codex 本地调试指导文档 - Pico 牵引小车 V1

这份文档用于之后在本地让 Codex 继续调试、改代码、排查接线问题。请先阅读本文，再修改 `main.py`。

## 2026-07-04 Pico-BLE 硬件更新

当前实物改为 Raspberry Pi Pico / RP2040 + Waveshare Pico-BLE/WS-B01 串口蓝牙底板。底板默认用 `GP0/GP1` 做蓝牙 UART0，`GP15` 是蓝牙模块 `STATE` 状态脚，不能再作为电机输出。

新版 `main.py` 已经把右电机 `INB2` 从 `GP15` 改到 `GP17`。之后接线和调试按这个新表为准：

| 功能 | Pico GPIO |
|---|---:|
| 蓝牙 UART0 TX/RX | GP0 / GP1 |
| 蓝牙 STATE | GP15 |
| 左 HX711 DOUT / SCK | GP6 / GP7 |
| 右 HX711 DOUT / SCK | GP8 / GP9 |
| 左电机 PWM / INA / INB | GP10 / GP11 / GP12 |
| 右电机 PWM / INA / INB | GP13 / GP14 / GP17 |
| 急停输入 | GP16 |

同日新增微信小程序调试端，目录为 `wechat_miniprogram/`。小程序通过 BLE GATT 扫描、连接、自动发现可写/可通知 characteristic，并发送 Pico 行命令：

```text
status
param
stream on/off
identify 5
auto
stop
tare
f/b/l/r 0.15
set max_pwm 0.35
```

Pico 回包格式使用 `info` / `param` / `stat` / `ok` / `err` 前缀，方便小程序解析。上电默认模式是 `idle`，蓝牙连接、传感器 `tare`、重启后都不能自动转动，需要明确发 `auto` 才进入拉力助力。板载 LED 固定用于蓝牙/动作/拉力反馈状态：未连接灭，已连接常亮，收到命令或电机输出时闪烁，传感器拉力越大闪烁越快，`identify 5` 会快闪 5 秒后自动恢复。

2026-07-05 增加临时标定参数：

```text
left_motor_gain / right_motor_gain
left_force_gain / right_force_gain
```

如果一边轮子因为临时扎带、阻力或机械安装导致速度偏快，优先查机械，也可以临时降低对应 `*_motor_gain`。如果左右 S 型传感器同样拉力下读数不一致，优先改善拉力传导，再用 `*_force_gain` 小范围补偿。

## 项目目标

做一台轻便取快递牵引电助力小车。V1 不做视觉、不做自动导航，先实现：

```text
双 S 型拉力传感器
  -> 双 HX711
  -> Raspberry Pi Pico / RP2040
  -> WHEELTEC D50A 双路有刷电机驱动板
  -> 两个 24V 210W 轮椅电机轮
```

控制逻辑：

```text
总拉力越大 -> 左右轮速度越大
左/右拉力差 -> 左右轮差速转向
松手/急停/传感器异常 -> 停止 PWM
```

## 当前硬件

| 模块 | 当前选择 |
|---|---|
| 主控 | Raspberry Pi Pico / RP2040，MicroPython |
| 电机 | 鱼跃轮椅电机轮，24V 210W，两根电机线，可正反转 |
| 电机驱动 | WHEELTEC D50A MOS 双路直流有刷电机驱动模块 |
| 电池 | 24V 锂电池，BMS 30A |
| 降压 | 24V 转 5V，建议 5V 6A |
| 拉力传感器 | S 型称重传感器 ×2 |
| ADC | HX711 ×2，建议 80Hz 模块 |
| 框架 | 2040 铝型材，后续做三孔法兰电机转接板和电池托盘 |

电机本体只有两根线，没有独立刹车线、编码器线、霍尔线。速度由驱动板 PWM 控制，正反转由驱动板 H 桥控制。

## 关键文件

| 文件 | 作用 |
|---|---|
| `main.py` | Pico 上运行的 MicroPython 主程序 |
| `README.md` | 常规接线、烧录、测试说明 |
| `CODEX_LOCAL_DEBUG_GUIDE.md` | 本文，给 Codex 的完整调试上下文 |

## 不要轻易改的原则

1. 不要把 24V 接到 Pico、HX711 或驱动板控制口。
2. 不要默认使用 `INA=1, INB=1` 作为刹车，因为卖家说明没有写刹车逻辑。
3. 不要把两个 S 型传感器并到一个 HX711。必须一边一个 HX711。
4. 不要在轮子落地时第一次测试电机。必须架空。
5. 不要一开始用满 PWM。V1 保持 `MAX_PWM = 0.45` 左右。
6. 不要把急停只当软件功能。最终急停应尽量硬件切断驱动器使能或动力支路，Pico 只做辅助检测。

## Pico 当前 GPIO 分配

这些引脚要和 `main.py` 保持一致。

| 功能 | Pico GPIO |
|---|---:|
| 左 HX711 DOUT / DT | GP6 |
| 左 HX711 SCK | GP7 |
| 右 HX711 DOUT / DT | GP8 |
| 右 HX711 SCK | GP9 |
| 左电机 PWM | GP10 |
| 左电机 INA | GP11 |
| 左电机 INB | GP12 |
| 右电机 PWM | GP13 |
| 右电机 INA | GP14 |
| 右电机 INB | GP17 |
| 急停输入 | GP16 |
| 板载 LED | GP25 |

急停默认接法：

```text
GP16 -> 急停按钮 -> GND
```

代码里使用 `Pin.PULL_UP`：

```text
松开 = 1
按下 = 0
```

## 总电源接线

```text
24V 电池正极
  -> 40A 主保险丝
  -> 总电源开关
  -> 分两路
       1. 驱动板动力电源 VP/V+
       2. 2A 控制支路保险丝 -> 24V 转 5V 降压模块 IN+

24V 电池负极
  -> 驱动板动力电源 GND/V-
  -> 降压模块 IN-
```

5V 控制电源：

```text
降压模块 5V OUT+
  -> 1A 或 2A 小保险丝
  -> Pico VSYS / 5V

降压模块 OUT-
  -> Pico GND
```

必须共地：

```text
Pico GND
HX711 GND
驱动板控制口 GND
降压模块 OUT-
电池负极/驱动板动力 GND
```

## WHEELTEC D50A 驱动板接线

驱动板每路控制口是：

```text
VCC / PWM / INA / INB / GND
```

接 Pico：

| 驱动板控制口 | Pico |
|---|---|
| 控制1 VCC | 3V3 |
| 控制1 PWM1 | GP10 |
| 控制1 INA1 | GP11 |
| 控制1 INB1 | GP12 |
| 控制1 GND | GND |
| 控制2 VCC | 3V3 |
| 控制2 PWM2 | GP13 |
| 控制2 INA2 | GP14 |
| 控制2 INB2 | GP17 |
| 控制2 GND | GND |

电机输出：

```text
驱动板 MOTOR1 两端 -> 左电机两根线
驱动板 MOTOR2 两端 -> 右电机两根线
```

如果方向反了，优先在代码里改：

```python
LEFT_MOTOR_REVERSE = True
RIGHT_MOTOR_REVERSE = True
```

也可以交换对应电机的两根线。

## 驱动板逻辑

按当前保守逻辑使用：

| PWM | INA | INB | 状态 |
|---|---|---|---|
| 0 | 0 | 0 | 停止/滑行 |
| PWM | 1 | 0 | 正转 |
| PWM | 0 | 1 | 反转 |
| PWM | 1 | 1 | 未知，不使用 |

卖家说明没有明确 `INA=1, INB=1` 是电子刹车，所以代码不要默认输出 `1/1`。

当前 `Motor.write()` 应保持类似逻辑：

```python
if duty <= 0:
    INA = 0
    INB = 0
elif command > 0:
    INA = 1
    INB = 0
else:
    INA = 0
    INB = 1
```

## HX711 接线

Pico 到 HX711：

| 模块 | HX711 引脚 | Pico |
|---|---|---|
| 左 HX711 | VCC | 3V3 |
| 左 HX711 | GND | GND |
| 左 HX711 | DT / DOUT | GP6 |
| 左 HX711 | SCK | GP7 |
| 右 HX711 | VCC | 3V3 |
| 右 HX711 | GND | GND |
| 右 HX711 | DT / DOUT | GP8 |
| 右 HX711 | SCK | GP9 |

HX711 到 S 型传感器：

```text
E+ -> 传感器激励正
E- -> 传感器激励负
A+ -> 传感器信号正
A- -> 传感器信号负
屏蔽线 -> GND 或只接车架一端
```

如果拉传感器时串口数值变负，改：

```python
LEFT_FORCE_SIGN = -1
RIGHT_FORCE_SIGN = -1
```

按实际哪边反了改哪边。

## macOS 烧录和连接

第一次刷 MicroPython：

1. 按住 Pico 的 `BOOTSEL`。
2. 插入 Mac USB。
3. Finder 出现 `RPI-RP2`。
4. 拖入对应 Pico 的 MicroPython `.uf2`。
5. Pico 自动重启，`RPI-RP2` 消失。

安装工具：

```bash
python3 -m pip install -U mpremote pyserial
```

查端口：

```bash
ls /dev/cu.usbmodem* /dev/tty.usbmodem* 2>/dev/null
python3 -m mpremote connect list
```

拷贝代码：

```bash
cd /workspace/pico_cart_control
python3 -m mpremote connect /dev/cu.usbmodemXXXX fs cp main.py :main.py
python3 -m mpremote connect /dev/cu.usbmodemXXXX reset
```

进入串口：

```bash
python3 -m mpremote connect /dev/cu.usbmodemXXXX
```

常用快捷键：

```text
Ctrl+C 停止程序
Ctrl+D 软重启
```

如果出现 `mpremote: no device found`：

```text
RPI-RP2 出盘 = BOOTSEL bootloader 模式，mpremote 找不到
/dev/cu.usbmodemXXXX 出现 = MicroPython 串口模式，mpremote 能连接
```

## 推荐调试顺序

### 1. 只测 Pico

先不接驱动板、不接 HX711，只插 USB。

目标：

```text
串口能连接
main.py 能运行
板载 LED 能按蓝牙/动作/拉力状态显示：未连接灭，已连接常亮，动作或命令闪烁，传感器拉力越大闪烁越快
```

### 2. 单独测驱动板和电机

在 `main.py` 顶部改：

```python
MOTOR_TEST_MODE = True
MOTOR_TEST_PWM = 0.20
```

轮子必须架空。程序会自动测试：

```text
left_forward
left_reverse
right_forward
right_reverse
both_forward
both_reverse
stop
```

如果某个轮方向反了，改：

```python
LEFT_MOTOR_REVERSE = True
RIGHT_MOTOR_REVERSE = True
```

测试完成后改回：

```python
MOTOR_TEST_MODE = False
```

### 3. 单独测 HX711

接两个 HX711 和两个 S 型传感器，不接电机动力或让驱动板断电。

启动时保持两个传感器不受力，等待 tare 完成。

串口观察：

```text
Lraw=...
Rraw=...
L=...
R=...
total=...
steer=...
```

判断：

```text
正前方拉：L 和 R 都增加，差不多接近
往左拉：左边明显更大
往右拉：右边明显更大
松手：L/R 接近 0
```

如果拉力为负，改 `LEFT_FORCE_SIGN` / `RIGHT_FORCE_SIGN`。

### 4. 合并拉力控制

确认电机和 HX711 单独正常后，再合并。

初始参数建议：

```python
MAX_PWM = 0.45
MIN_MOVE_PWM = 0.14
PULL_START_RAW = 25000
PULL_FULL_RAW = 180000
STEER_GAIN = 0.75
RAMP_STEP = 0.018
```

如果轻拉不动：

```text
降低 PULL_START_RAW
或提高 MIN_MOVE_PWM
```

如果太灵敏：

```text
提高 PULL_START_RAW
或降低 STEER_GAIN
```

如果起步太冲：

```text
降低 RAMP_STEP
降低 MAX_PWM
```

## 常见问题排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| Pico 出现 RPI-RP2 盘 | 处于 BOOTSEL 模式 | 刷 MicroPython UF2，重启后再用 mpremote |
| mpremote 找不到设备 | 未刷 MicroPython、线不支持数据、端口被占用 | 换数据线，查 `/dev/cu.usbmodem*`，关闭 Thonny/串口助手 |
| HX711 timeout | DOUT/SCK 接反、HX711 没供电、GND 未共地 | 检查 3V3/GND/DT/SCK |
| 拉力读数反向 | 传感器方向或线序导致 | 改 `LEFT_FORCE_SIGN` / `RIGHT_FORCE_SIGN` |
| 电机不动 | 控制口 VCC/GND 未接、PWM 引脚错、动力电源未接 | 检查驱动板控制口和 24V 动力口 |
| 电机方向反 | 电机线顺序或安装方向相反 | 改 `*_MOTOR_REVERSE` 或交换电机两线 |
| 轮子一启动很猛 | PWM 太高、起步阈值太低 | 降低 `MAX_PWM`、`MIN_MOVE_PWM`，降低 `RAMP_STEP` |
| 驱动板发热 | 负载过大、堵转、PWM 太高、散热不足 | 架空测试，限流/限速，增加散热 |

## 机械结构上下文

电机是三孔三角法兰孔位，不是普通圆法兰。后续安装到 2040 铝型材建议：

```text
电机三孔法兰
  -> 6mm 铝板或 5mm 钢板转接板
  -> 一字长圆孔调节
  -> 2040 T 型螺母固定
```

搜索/加工关键词：

```text
轮椅电机 三孔法兰 安装板
三孔法兰 转接板 定制
2040铝型材 电机安装板 长圆孔
2040铝型材 可调电机支架
```

电池尺寸：

```text
150 × 80 × 70mm
```

2040 铝材高度 40mm，电池高度 70mm，必须做专门电池托盘。建议内尺寸至少：

```text
170 × 100 × 80mm
```

更舒服：

```text
180 × 110 × 90mm
```

电池托盘建议：

```text
2~3mm 铝板 / 5mm 亚克力 / 6mm 木板
两条 20~25mm 魔术扎带
底部泡棉或橡胶垫防震
主保险丝靠近电池正极
```

## 后续让 Codex 改代码时的推荐提示词

可以直接这样对 Codex 说：

```text
请先阅读 /workspace/pico_cart_control/CODEX_LOCAL_DEBUG_GUIDE.md、
/workspace/pico_cart_control/README.md 和 /workspace/pico_cart_control/main.py。

这是一个 Raspberry Pi Pico + WHEELTEC D50A + 双 HX711 的 24V 轮椅电机牵引小车项目。
不要改变现有 GPIO 分配，除非我明确要求。
不要使用 INA=INB=1 的刹车模式，因为驱动板没有明确说明支持。
请按当前硬件约束修改 main.py，并给出 macOS 下 mpremote 烧录命令。
```

## 当前阶段结论

V1 当前优先级：

```text
1. Pico 能稳定运行 main.py
2. 电机测试模式能控制两个轮子正反转
3. 双 HX711 能稳定读取左右拉力
4. 拉力控制差速逻辑能在架空状态下稳定运行
5. 再购买和加工 2040 框架、电机转接板、电池托盘
```

蓝牙现在只作为低层串口调试/手动低速测试入口，不负责复杂智能控制。底层运动控制仍要先简单可靠；未来语音、DeepSeek API 或自动编程等智能功能应使用上层 Linux 主机或手机，Pico 只负责实时控制和安全停车。
