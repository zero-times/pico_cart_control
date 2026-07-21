# Pico-BLE 小车控制程序

这套代码用于 Raspberry Pi Pico / RP2040 + Waveshare Pico-BLE/WS-B01 串口蓝牙底板 + 双 HX711 + WHEELTEC D50A 双路电机驱动。

`main.py` 拷贝到 Pico 根目录后会自动运行。程序默认仍然按“拉力传感器牵引助力”工作，同时增加蓝牙串口命令，方便用手机或电脑调试。

## 当前关键变化

Pico-BLE 底板会占用这些引脚：

| 功能 | Pico GPIO |
|---|---:|
| 蓝牙 UART0 TXD | GP0 |
| 蓝牙 UART0 RXD | GP1 |
| 蓝牙 STATE 状态脚 | GP15 |

所以旧程序里右电机 `INB2 = GP15` 必须改线。新版程序已经改成：

```text
右电机 INB2 -> GP17
GP15        -> 只接蓝牙模块 STATE，不能再接电机驱动输入
```

## 新版默认引脚

| 功能 | Pico GPIO |
|---|---:|
| 蓝牙模块 TX/RX | GP0 / GP1 |
| 蓝牙模块 STATE | GP15 |
| 左 HX711 DOUT | GP6 |
| 左 HX711 SCK | GP7 |
| 右 HX711 DOUT | GP8 |
| 右 HX711 SCK | GP9 |
| 左电机 PWM | GP10 |
| 左电机 INA | GP11 |
| 左电机 INB | GP12 |
| 右电机 PWM | GP13 |
| 右电机 INA | GP14 |
| 右电机 INB | GP17 |
| 急停输入 | GP16 |
| 前方红外避障 OUT | GP22 |

急停默认接法：

```text
GP16 -> 急停开关 -> GND
```

松开为 1，按下为 0。

前方红外避障模块默认使用低电平触发：

```text
红外模块 VCC -> Pico 3V3
红外模块 GND -> Pico GND
红外模块 OUT -> Pico GP22（物理针脚 29）
```

不要把模块的 `5V OUT` 直接接入 Pico。程序在 `GP22` 下降沿出现时立即
硬停电机，并在信号连续恢复 400ms 后解除障碍锁。障碍触发会清除原来的
前进目标，禁止前进和原地转向；仍可重新发送后退命令脱困。牵引模式触发
后必须先松开牵引绳重新解锁，不会在障碍消失后自动继续前进。

状态日志中：

```text
front=1         # 障碍锁已触发
front_signal=1  # 传感器当前仍输出障碍信号
drive=front_obstacle
```

## WHEELTEC D50A 接线

驱动板每路控制口是：

```text
VCC / PWM / INA / INB / GND
```

建议 Pico 这样接：

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

不要把 24V 接到 Pico。驱动板控制口 `VCC` 用 Pico 的 `3V3`，不是 24V。

## HX711 接线

你图片里这款红色 HX711 模块，左侧 4 个针脚接 Pico：

| HX711 左侧丝印 | Pico |
|---|---|
| VCC | 3V3 |
| DO/RX | 左模块接 GP6，右模块接 GP8 |
| CK/TX | 左模块接 GP7，右模块接 GP9 |
| GND | GND |

右侧 6 个针脚接 S 型拉力传感器：

| HX711 右侧丝印 | 常见含义 | S 型传感器 |
|---|---|---|
| out+ | 激励正 / E+ | E+ |
| A- | A 通道信号负 | A- / S- |
| A+ | A 通道信号正 | A+ / S+ |
| GND | 激励负 / E- | E- |
| B+ | B 通道信号正 | 不接 |
| B- | B 通道信号负 | 不接 |

本项目每个 S 型拉力传感器用一个 HX711，默认只用 A 通道，`B+ / B-` 先不接。

你现在这款 10kg S 型传感器标签上的线序是：

| 传感器线色 | 标签含义 | 接 HX711 |
|---|---|---|
| 红 | 电源正 | out+ |
| 黑 | 电源负 | GND |
| 绿 | 信号正 | A+ |
| 白 | 信号负 | A- |

照片里的接法看起来就是这个顺序：红接 `out+`，白接 `A-`，绿接 `A+`，黑接 `GND`。

左、右两个 HX711 分别这样接 Pico：

| 模块 | VCC | GND | DO/RX | CK/TX |
|---|---|---|---|---|
| 左 HX711 | 3V3 | GND | GP6 | GP7 |
| 右 HX711 | 3V3 | GND | GP8 | GP9 |

## 拉力传导机械建议

S 型传感器读数不顺畅时，优先查机械，不要先调代码：

1. 传感器两端尽量用关节轴承/鱼眼接头，避免侧向力和扭力。
2. 拉力方向要穿过传感器中心线，不要让扎带斜拉或绕过边角。
3. 两边传感器固定高度和拉绳角度尽量一致。
4. 传感器本体不要被扎带压住、顶住或与车架摩擦。
5. 线缆要留松弛，不要让传感器线变成额外受力路径。
6. 测试时先在小程序打开 `stream on`，慢慢拉左/右，看 `l` 和 `r` 是否平滑上升。
7. 机械顺畅后，再用 `left_force_gain/right_force_gain` 做小范围比例补偿。

## 蓝牙串口

Pico-BLE 模块默认波特率通常是 `115200 bps`，新版程序也按 `115200` 配置：

```python
BLE_UART_ID = 0
BLE_UART_TX = 0
BLE_UART_RX = 1
BLE_UART_BAUD = 115200
```

如果你把底板焊盘切到 UART1，改成：

```python
BLE_UART_ID = 1
BLE_UART_TX = 4
BLE_UART_RX = 5
```

蓝牙连接后可以发这些命令：

```text
help
ver
info
status
param
stream on
stream off
hwlog dump
hwlog status
hwlog clear
identify 5
auto
manual
idle
stop
tare
drive 0.15 0.15
f 0.15
b 0.15
l 0.15
r 0.15
pins
motor left f 0.18 1500
motor right f 0.18 1500
motor both b 0.18 1500
set max_pwm 0.35
set start_raw 25000
set full_raw 180000
set left_motor_gain 1.00
set right_motor_gain 0.90
set left_force_gain 1.00
set right_force_gain 1.00
```

`drive/f/b/l/r` 是手动电机测试命令，程序限制为 `MANUAL_MAX_PWM = 0.25`，并且 `700ms` 内没有新命令就自动停。

如果一边轮子明显更快，可以临时用电机增益修正。例如右轮更快：

```text
set right_motor_gain 0.90
```

如果一边拉力传感器读数明显偏大/偏小，可以临时用传感器增益修正。例如右传感器读数只有左边一半：

```text
set right_force_gain 2.00
```

这些增益是临时调试参数，断电后会恢复代码里的默认值。

板载 LED 状态：

```text
未连接蓝牙：灭
已连接蓝牙且空闲：常亮
收到命令或电机正在输出：闪烁
传感器有拉力反馈：拉力越大，闪烁越快
identify 5：快闪 5 秒，然后自动回到上述状态
```

程序回包是给小程序解析的行协议：

```text
info proto=pico-cart-ble-2026-07-04 uart=UART0 tx=GP0 rx=GP1 state=GP15 right_inb=GP17 auto_start=0
param max_pwm=0.45 min_pwm=0.14 start_raw=25000 full_raw=180000 steer_gain=0.75 ramp=0.018 manual_max=0.25 timeout_ms=700
stat mode=idle sensor=ok err=- lraw=0 rraw=0 l=0 r=0 total=0 steer=0.00 pwml=0.00 pwmr=0.00 estop=1 bt=1 unsafe=-
ok stop
err unknown_cmd
```

新版程序上电默认是 `idle`，不会自动进入拉力助力；蓝牙连接、传感器 `tare`、重启后都不会自动转动。只有在小程序或串口里明确发 `auto` 后，才进入自动牵引模式。

## 微信小程序调试端

小程序工程在：

```text
wechat_miniprogram/
```

使用方式：

1. 用微信开发者工具打开 `D:\workspace\pico_cart_control\wechat_miniprogram`。
2. AppID 可以先用测试号或 `touristappid` 调试。
3. 真机预览或真机调试时打开手机蓝牙。
4. 点 `蓝牙`，再点 `扫描`。
5. 在设备列表里选择 Pico-BLE/WS-B01 模块。
6. 连接后小程序会自动查询 `status` 和 `param`。

小程序会自动寻找常见 BLE 串口 UUID，例如 Nordic UART `6E400001...`、`FFE0/FFE1`、`FFF0/FFF1`，也会兜底选择同一个 service 下的“可写 characteristic + 可 notify characteristic”。如果模块只支持经典蓝牙 SPP、不开放 BLE GATT，小程序扫描不到或连不上；此时微信小程序无法直接使用经典蓝牙 SPP，需要把模块切到 BLE 模式，或换支持 BLE UART/GATT 的模块。

设备列表里可以点设备行直接连接，也可以点 `连接闪灯`：小程序会先连接该设备，然后发送 `identify 5`，板载 LED 快闪 5 秒，用来确认哪一个蓝牙设备是这块 Pico。

手动方向键是按住持续发送、松手发送 `stop`。轮子第一次测试必须架空。

日志区域支持一次打包：

| 按钮 | 作用 |
|---|---|
| 发送好友 | 分享最近一次已保存的 `.txt` 日志文件 |
| 保存文件 | 生成/刷新日志文件，写入小程序本地目录 `wx.env.USER_DATA_PATH` |
| 复制日志 | 把完整日志文本复制到剪贴板 |
| 清空 | 清空屏幕日志和内部导出日志 |

微信限制 `wx.shareFileMessage` 必须由用户点击直接触发，所以发送文件时按两步操作：先点 `保存文件`，再点 `发送好友`。如果仍提示 TAP gesture，就用 `复制日志` 发送文本。

日志文件包含生成时间、设备名/ID、BLE service/characteristic、当前状态、当前参数和收发记录。屏幕只显示最近 80 条，导出会保留最近 1000 条。

如果点方向没有反应，先看小程序日志：

```text
> f 0.16
< ok drive left=0.16 right=0.16
```

有 `>` 但没有 `<`，重点查 BLE 写入和 Pico 收包；连 `>` 都没有，说明小程序触摸事件没有触发或页面没有重新编译。

### 电机不动的专用诊断

如果电机直接接 24V 正反转都正常，但 Pico 蓝牙控制不动，先不要用方向键，直接在小程序命令框发：

```text
pins
motor left f 0.18 1500
motor left b 0.18 1500
motor right f 0.18 1500
motor right b 0.18 1500
```

正常时日志会出现：

```text
< pins left_pwm=GP10 left_ina=GP11 left_inb=GP12 right_pwm=GP13 right_ina=GP14 right_inb=GP17 estop=GP16 ble_state=GP15
< ok motor side=left dir=f power=0.18 ms=1500
< ok motor_done
```

如果有 `ok motor...` 但电机不动，说明 Pico 已经在输出控制，重点查硬件：

1. D50A 控制口 `VCC` 有没有接 Pico `3V3` 或驱动板要求的控制电源。
2. D50A 控制口 `GND` 有没有接 Pico `GND`。
3. Pico GND、D50A 控制 GND、24V 电池负极/D50A 动力 GND 是否共地。
4. 左路是否按 `GP10/GP11/GP12` 接到 `PWM1/INA1/INB1`。
5. 右路是否按 `GP13/GP14/GP17` 接到 `PWM2/INA2/INB2`，不要再接 `GP15`。
6. D50A 控制口如果是光耦输入，`3.3V` 可能不够，需要按驱动板说明把控制 `VCC` 接 `5V`，并确认 Pico 的 `3.3V` GPIO 能被识别。
7. 急停 `GP16` 如果被拉到 GND，程序会停；状态里应看到 `estop=1`。

### 蓝牙隐私权限

如果真机提示：

```text
openBluetoothAdapter:fail api scope is not declared in the privacy agreement
```

这不是 Pico 或蓝牙代码问题，而是微信小程序后台没有在“用户隐私保护指引”里声明蓝牙用途。处理方式：

1. 不要用 `touristappid` 做真机蓝牙调试，换成你自己的真实小程序 AppID。
2. 进入微信公众平台小程序后台。
3. 找到 `设置` / `服务内容声明` / `用户隐私保护指引`。
4. 在涉及的接口或权限里添加蓝牙相关用途，例如用于连接 Pico-BLE 调试小车控制器。
5. 保存并发布/生效后，重新用微信开发者工具预览或真机调试。

小程序代码里已经调用 `wx.requirePrivacyAuthorize`，但前提是后台隐私保护指引已经声明了蓝牙权限。

## Windows 烧录和连接

Pico 只有按住 `BOOTSEL` 插入 USB 时才会显示 `RPI-RP2` 盘。已经刷过 MicroPython 后，正常插入不会出盘，而是显示为串口 COM。

1. 按住 Pico 上的 `BOOTSEL`。
2. 插入 USB。
3. 松开 `BOOTSEL`。
4. Windows 应该出现 `RPI-RP2` 盘。
5. 把对应 Pico 的 MicroPython `.uf2` 拖进去。
6. Pico 自动重启，盘会消失。

安装工具：

```powershell
py -m pip install -U mpremote pyserial
```

查端口：

```powershell
py -m mpremote connect list
```

复制程序：

```powershell
cd D:\workspace\pico_cart_control
py -m mpremote connect COMx fs cp main.py :main.py
py -m mpremote connect COMx reset
```

把 `COMx` 换成实际端口，比如 `COM5`。

## 未知 USB 设备排查

设备管理器里如果是 `未知 USB 设备(设备描述符请求失败)`，这通常不是“正常进入串口模式”，而是 USB 枚举失败。先按这个顺序查：

1. 换一根确认能传数据的 USB 线，不要只充电线。
2. 不接 24V、电机驱动、HX711，只接 Pico USB。
3. 按住 `BOOTSEL` 再插 USB，看是否出现 `RPI-RP2`。
4. 换电脑直连 USB 口，不走扩展坞。
5. 如果底板 USB-C 不行，试 Pico 自己的 USB 口；如果 Pico 自己 USB 口不方便，检查底板到 Pico 的 USB 焊接/接触。
6. 在设备管理器删除那个未知设备后重新插入。

只要 `BOOTSEL` 模式都不能出 `RPI-RP2`，先不要继续接电机测试，优先解决 USB 数据连接或焊接问题。

## mpremote 无法进入 raw REPL

如果执行：

```powershell
python -m mpremote connect COM3 fs cp main.py :main.py
```

出现：

```text
mpremote.transport.TransportError: could not enter raw repl
```

并且 Windows 设备详情里显示 `CircuitPython CDC control`，说明 Pico 当前刷的是 CircuitPython，不是 MicroPython。这个项目的 `main.py` 使用 MicroPython 的 `machine.Pin/PWM/UART`，不能直接运行在 CircuitPython 上。

处理方式：

1. 按住 Pico 的 `BOOTSEL`。
2. 插入 USB。
3. Windows 出现 `RPI-RP2` 盘。
4. 从 [MicroPython Pico 下载页](https://micropython.org/download/RPI_PICO/) 下载 `RPI_PICO` 的稳定版 `.uf2`。
5. 把 `.uf2` 拖进 `RPI-RP2` 盘，等待 Pico 自动重启。
6. 再执行：

```powershell
cd D:\workspace\pico_cart_control
python -m mpremote connect list
python -m mpremote connect COMx fs cp main.py :main.py
python -m mpremote connect COMx reset
```

注意必须先 `cd D:\workspace\pico_cart_control`，否则 `main.py` 会从 `C:\Users\Administrator` 下面找。

如果当前还能连上 CircuitPython 串口，也可以不按 BOOTSEL，直接让它重启进 bootloader：

```powershell
python -m mpremote connect COM3 resume exec "import microcontroller; microcontroller.on_next_reset(microcontroller.RunMode.BOOTLOADER); microcontroller.reset()"
```

执行后再看 Windows 是否出现 `RPI-RP2` 盘，然后拖入 MicroPython `.uf2`。

## 第一次测试顺序

1. 只接 Pico/Pico-BLE，不接电机、不接 24V，看 Windows 能否识别 `RPI-RP2` 或 COM。
2. 刷入 MicroPython，再用 `mpremote` 复制 `main.py`。
3. 蓝牙连接后发 `status`，确认能收到回复。
4. 接两个 HX711，保持传感器不受力，发 `tare`。
5. 轮子架空，低速发 `f 0.12`、`b 0.12` 测电机方向。
6. 确认电机方向和传感器读数后，再进入 `auto` 拉力助力模式。

## 必须调整的参数

在 `main.py` 顶部改：

```python
PULL_START_RAW = 25000
PULL_FULL_RAW = 180000
LEFT_FORCE_SIGN = 1
RIGHT_FORCE_SIGN = 1
LEFT_MOTOR_REVERSE = False
RIGHT_MOTOR_REVERSE = False
LEFT_MOTOR_GAIN = 1.0
RIGHT_MOTOR_GAIN = 1.0
LEFT_FORCE_GAIN = 1.0
RIGHT_FORCE_GAIN = 1.0
MOTOR_TEST_MODE = False
```

如果拉传感器时数值变成负数，把对应 `FORCE_SIGN` 改成 `-1`。

如果某个电机方向反了，把对应 `MOTOR_REVERSE` 改成 `True`。

如果某个轮子速度偏快，把对应 `MOTOR_GAIN` 降低，比如 `0.90`。

如果某个传感器同样拉力下读数偏大/偏小，调对应 `FORCE_GAIN`。

如果只想先测试电机驱动板，不接 HX711，把：

```python
MOTOR_TEST_MODE = True
```

两个轮子必须架空。

## Android 调试端

Android 原生 App 工程在：

```text
android_app/
```

当前 Android 端已经迁移微信小程序的核心调试功能：

1. BLE 权限申请、扫描、停止扫描、连接和断开。
2. 自动选择常见 BLE UART/GATT 通道：Nordic UART `6E400001...`、`FFE0/FFE1`、`FFF0/FFF1`，并兜底选择同一 service 下的可写 characteristic 与 notify characteristic。
3. 连接后自动发送 `status` 和 `param`。
4. 解析 `stat`、`param`、`info`、`ok stream=...` 行协议。
5. 支持 `stop`、`auto`、`manual`、`tare`、`identify 5`、`stream on/off`、`status`、自定义命令。
6. 支持手动功率滑块和方向键长按重复发送 `f/b/l/r`，松手自动 `stop`。
7. 支持参数刷新、逐项写入、日志复制、保存和分享。
8. 增加文字转语音抽象层，状态页可以朗读当前模式、传感器、拉力和 PWM。
9. 增加轻量 Agent Host V1：Android App 调 DeepSeek Tool Calls，本地执行安全工具，再通过 BLE 给 Pico 发命令。

构建调试 APK：

```bash
cd android_app
./gradlew :app:assembleDebug
```

生成文件：

```text
android_app/app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的 Android 设备：

```bash
cd android_app
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 12 及以上会请求 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`；Android 11 及以下会请求蓝牙扫描所需的位置权限。BLE 调试不需要网络；AI 助手页会使用 `INTERNET` 权限调用 DeepSeek API。
语音唤醒会请求 `RECORD_AUDIO` 权限。

### Android ADB 调试日志

Android App 会持续把运行日志落盘到应用外部私有目录，不需要额外存储权限：

```text
/sdcard/Android/data/com.zerotimes.picocart/files/logs/mambo_voice_debug.log
/sdcard/Android/data/com.zerotimes.picocart/files/logs/pico_cart_debug.log
```

语音唤醒、VAD、STT、唤醒候选、命令候选、曼波朗读等链路优先看：

```bash
adb pull /sdcard/Android/data/com.zerotimes.picocart/files/logs/mambo_voice_debug.log .
adb shell tail -n 120 /sdcard/Android/data/com.zerotimes.picocart/files/logs/mambo_voice_debug.log
```

如果要关联 BLE、Agent、工具调用和普通 App 日志：

```bash
adb pull /sdcard/Android/data/com.zerotimes.picocart/files/logs/pico_cart_debug.log .
adb shell tail -n 120 /sdcard/Android/data/com.zerotimes.picocart/files/logs/pico_cart_debug.log
```

每个日志文件超过约 `2MB` 会自动轮转，保留 `.1`、`.2`、`.3` 三份历史文件。

### Pico 硬件诊断日志

Pico 会在 RAM 中维护最近 `192` 条硬件诊断记录，不会在行驶过程中写 Flash。
进入 Android App 的 `调试` 页，连接并收到 Pico 心跳后，点击 `导出硬件日志`；App 会发送
`hwlog dump`，Pico 通过 BLE UART 逐条回传，Android 会以 `[hardware]` 分类追加到同一份
`pico_cart_debug.log` 中。导出进度只显示在界面，不会把整批记录刷进命令日志。

Pico 端也支持直接发送：

```text
hwlog status    # 查看缓存条数和导出状态
hwlog dump      # 开始 BLE 分批回传
hwlog clear     # 清空 RAM 环形缓存
```

记录包含蓝牙连接/断开、命令、手动保活超时、HX711 左右读取异常与恢复、急停/障碍/传感器
安全停车、模式切换、软停止和每 250ms 的运行快照。发生“突然停止”后，可同时拉取 App 日志：

```bash
adb pull /sdcard/Android/data/com.zerotimes.picocart/files/logs/pico_cart_debug.log .
```

## Android Agent Host V1

最新设计指导见：

```text
ANDROID_DEEPSEEK_TOOL_CALL_AGENT_GUIDE.md
```

当前 V1 已实现：

1. `SessionStore`：SQLite 保存 `sessions`、`messages`、`tool_calls`、`cart_status_log`。
2. `DeepSeekClient`：通过 `https://api.deepseek.com/chat/completions` 调用 DeepSeek，支持 `tools` 和 `tool_calls`。
3. `ToolRegistry`：内置 `cart_get_status`、`cart_stop`、`cart_set_mode`、`cart_move`、`cart_turn`。
4. `SafetyGuard`：拒绝未知工具、急停/故障下的移动、超长 duration、超限 speed_level、未解锁移动。
5. `AgentRuntime`：执行 tool-call 循环，保存工具日志，把 tool result 发回 DeepSeek。
6. `CartHardware` BLE 适配：把工具调用转换为现有 Pico 行协议，例如 `status`、`stop`、`manual`、`auto`、`f/b/l/r <power>`。
7. Compose UI：底部入口分为 `调试` 和 `助手`，助手页可填写 DeepSeek API Key、查看对话/工具日志，并手动解锁移动工具。
8. 曼波语音链路：助手角色名为“曼波”；打开 `曼波唤醒` 后，App 使用 `AudioRecord` 常驻采样、本地 VAD 切段、本地 Vosk grammar 识别唤醒词和短命令。说出“你好曼波”或“曼波小车”后进入 `听指令`，再把后面一段短命令转文字发送给 DeepSeek；`停车`、`急停`、`取消` 会本地直接执行。
9. 曼波朗读协议：DeepSeek 最终响应中的 `<mambo_say>...</mambo_say>` 会被 App 提取并朗读；普通响应仍显示在对话里并照常执行工具调用。

安全边界：

1. DeepSeek 只返回工具调用建议，不直接控制硬件。
2. Android App 本地校验工具名和参数。
3. `cart_move` 和 `cart_turn` 默认被 UI 移动锁拦截，必须在助手页手动解锁。
4. Pico 固件仍必须保留看门狗、急停、缓启动、限速和传感器异常停车。
5. 当前没有提供 `raw_serial_write`、`raw_gpio_write`、`set_left_pwm`、`disable_estop` 这类裸控制工具。

DeepSeek API Key 保存在 Android App 本地 `SharedPreferences`，不会写入仓库。默认模型使用 DeepSeek 当前推荐的 `deepseek-v4-flash`，thinking 默认关闭。

语音使用方式：

1. 进入 Android App 的 `助手` 页。
2. 填写 DeepSeek API Key。
3. 安装离线中文模型：

```bash
cd android_app
./scripts/download_vosk_cn_model.sh
./gradlew :app:assembleDebug
```

4. 打开 `曼波唤醒`，授予录音权限。
5. 先说 `你好曼波` 或 `曼波小车`，等状态变成 `听指令`。
6. 再说短命令：`读取状态`、`自检`、`进入调试模式`、`停车`、`急停`。
7. App 会显示识别到的文本、工具执行日志和普通响应，并朗读 `mambo_say` 片段。

唤醒成功后，App 会在当前页面上层弹出曼波语音动画；动画下方显示当前听到/识别到的文案。命令语音结束后，识别文本会自动进入本地命令执行或发送给 DeepSeek API。

当前语音唤醒是 App 内监听，不是系统级后台热词。需要 App 运行且 `曼波唤醒` 开关打开。语音链路不再调用 Android `SpeechRecognizer`，因此不会触发系统识别服务的开始/结束提示音；如果没有安装 `model-cn`，助手页会提示“未安装本地语音模型”并保持关闭。

如果一直停在 `待唤醒`，看助手日志里的诊断：

1. 出现 `VAD CALIBRATING`：启动后正在采集 2 秒底噪。
2. 出现 `VAD POSSIBLE_SPEECH` / `VAD SPEECH_STARTED`：说明连续语音能量超过动态阈值。
3. 出现 `VAD REJECTED_NOISE`：说明片段太短或能量不足，已丢弃并冷却 1 秒。
4. 出现 `唤醒候选：...，score=...`：说明本地 STT 已识别出文本，App 会按文本、拼音和 VAD 置信度计算 wake_score。
5. 没有任何诊断：检查系统是否把麦克风权限授给 App，或是否有其他应用占用麦克风。

曼波朗读优先使用内置 mp3 短句，当前本地缓存语音放在：

```text
android_app/app/src/main/assets/mambo_voice/
```

已内置 `我在`、`自检完成`、`已停车`、`干完了`。这些短句会直接播放 mp3；其他没有命中的文本继续回退到 Android 系统 TTS。

## NekoSpeak 文字转语音模块

已从上游拉取 NekoSpeak 源码到：

```text
third_party/NekoSpeak/
```

拉取的上游 HEAD 记录在：

```text
third_party/NekoSpeak.UPSTREAM
```

NekoSpeak 是一个完整 Android TTS Engine 应用，包含 TTS Service、ONNX Runtime、JNI、模型资源和设置界面，不是可直接 `implementation(project(...))` 的轻量库。当前 Pico Cart Android App 先通过 `SpeechEngine` 抽象接入系统 `TextToSpeech`，后续如果要完全使用 NekoSpeak 离线引擎，建议把 `third_party/NekoSpeak/app/src/main/java/com/nekospeak/tts` 中的 engine/service 代码拆成 Android library module，再实现 `com.zerotimes.picocart.speech.SpeechEngine`。
