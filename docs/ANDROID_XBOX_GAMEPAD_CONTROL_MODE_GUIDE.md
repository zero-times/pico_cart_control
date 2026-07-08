Android 小车 App：Xbox 蓝牙手柄控制模式实现文档
1. 目标

在现有 Android 小车控制 App 中新增 Xbox 蓝牙手柄驾驶模式。

推荐架构：

Xbox One S 蓝牙手柄
  ↓ 蓝牙 HID
Android App
  ↓ MotionEvent / KeyEvent
GamepadController
  ↓ 生成安全驾驶指令
现有蓝牙通讯模块
  ↓
Pico
  ↓
电机驱动板

不建议：

Xbox 手柄直接连 Pico

原因：

1. Xbox 手柄是蓝牙 HID 设备，Pico 直接做 HID Host 比较麻烦。
2. Android 原生更适合读取手柄输入。
3. Pico 应继续只做底层安全控制。
4. Android 可以统一处理 UI、语音、DeepSeek、蓝牙、手柄输入。
2. 新增控制模式

新增控制源：

enum class ControlSource {
    PHONE_STEERING,
    VOICE_AGENT,
    GAMEPAD,
    TOW_FOLLOW,
    DEBUG
}

新增手柄状态：

data class GamepadState(
    val connected: Boolean = false,
    val deviceId: Int? = null,
    val deviceName: String? = null,

    val steerRaw: Float = 0f,
    val throttleRaw: Float = 0f,
    val reverseRaw: Float = 0f,

    val steer: Float = 0f,
    val throttle: Float = 0f,
    val reverse: Float = 0f,

    val enableHeld: Boolean = false,
    val stopPressed: Boolean = false,

    val gear: Gear = Gear.N,
    val speedLevel: Int = 1,

    val lastInputAtMs: Long = 0L
)

enum class Gear {
    P, N, D, R
}
3. 推荐按键映射
手柄输入	功能
左摇杆 X 轴	左右转向
RT 右扳机	前进油门
LT 左扳机	后退油门 / 倒车
RB 按住	使能，松开立即停车
B	停车
A	确认 / 自检确认
Y	提高速度档
X	降低速度档
十字键上	D 档
十字键下	R 档
十字键左	N 档
十字键右	P 档
Menu / Start	进入或退出手柄模式
View / Back	返回普通驾驶模式

核心安全规则：

不按 RB，小车绝对不走。
按住 RB + RT，小车前进。
按住 RB + LT，小车后退。
松开 RB，立即停车。
按 B，立即停车。

这个设计比单纯用 RT/LT 控制更安全。

4. 速度档设计
档位	最大 PWM	用途
G1	20%	室内 / 调试
G2	35%	小区慢速
G3	50%	空旷地
G4	65%	默认锁定，后期开放

第一版只开放：

G1 / G2 / G3

倒车单独限速：

R 最大 20%~25%
5. Android 手柄输入读取

Android 通过 MotionEvent 和 KeyEvent 读取手柄。

判断事件来源：

private fun isGamepadSource(source: Int): Boolean {
    return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
           source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

在 Activity 层接收：

override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (isGamepadSource(event.source)) {
        gamepadController.onMotionEvent(event)
        return true
    }
    return super.dispatchGenericMotionEvent(event)
}

override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (isGamepadSource(event.source)) {
        gamepadController.onKeyEvent(event)
        return true
    }
    return super.dispatchKeyEvent(event)
}

如果是 Compose 项目，也建议仍然在 Activity 层统一接收，然后交给 ViewModel 或 Controller。

6. Axis 兼容

不同安卓设备、不同手柄固件，扳机轴可能不一样。

常见轴：

左摇杆 X：AXIS_X
左摇杆 Y：AXIS_Y

RT：
AXIS_RTRIGGER
AXIS_GAS
AXIS_Z / AXIS_RZ

LT：
AXIS_LTRIGGER
AXIS_BRAKE
AXIS_Z / AXIS_RZ

建议先做 GamepadDebugPanel，实时显示所有轴值，确认你的安卓手机上 Xbox 手柄实际怎么上报。

读取函数示例：

private fun MotionEvent.axisOrZero(axis: Int): Float {
    return try {
        getAxisValue(axis)
    } catch (_: Exception) {
        0f
    }
}

private fun readRightTrigger(event: MotionEvent): Float {
    val candidates = listOf(
        event.axisOrZero(MotionEvent.AXIS_RTRIGGER),
        event.axisOrZero(MotionEvent.AXIS_GAS)
    )
    return candidates.maxByOrNull { kotlin.math.abs(it) }?.coerceIn(0f, 1f) ?: 0f
}

private fun readLeftTrigger(event: MotionEvent): Float {
    val candidates = listOf(
        event.axisOrZero(MotionEvent.AXIS_LTRIGGER),
        event.axisOrZero(MotionEvent.AXIS_BRAKE)
    )
    return candidates.maxByOrNull { kotlin.math.abs(it) }?.coerceIn(0f, 1f) ?: 0f
}
7. GamepadController 设计

新增文件建议：

gamepad/
  GamepadController.kt
  GamepadState.kt
  GamepadMapper.kt
  GamepadInputReader.kt
  GamepadDriveCommandFactory.kt

职责：

1. 解析 MotionEvent / KeyEvent。
2. 维护 GamepadState。
3. 应用死区、曲线、速度档。
4. 生成 DriveCommand。
5. 不直接操作蓝牙。

死区处理：

fun applyDeadZone(value: Float, deadZone: Float): Float {
    val abs = kotlin.math.abs(value)
    if (abs < deadZone) return 0f

    val normalized = (abs - deadZone) / (1f - deadZone)
    return kotlin.math.sign(value) * normalized.coerceIn(0f, 1f)
}

推荐参数：

摇杆死区：0.15
扳机死区：0.08
8. 差速控制算法

输入：

throttle: 0..1
reverse: 0..1
steer: -1..1
gear: D/R/N/P
speedLevel: 1..3
enableHeld: Boolean

速度限制：

val speedLimit = when (speedLevel) {
    1 -> 0.20f
    2 -> 0.35f
    3 -> 0.50f
    else -> 0.20f
}

val reverseLimit = 0.25f

基础速度：

val base = when (gear) {
    Gear.D -> throttle * speedLimit
    Gear.R -> -reverse * reverseLimit
    Gear.N -> 0f
    Gear.P -> 0f
}

左右轮差速：

val turnGain = 0.65f
val turn = steer * turnGain * kotlin.math.abs(base)

var left = base - turn
var right = base + turn

left = left.coerceIn(-speedLimit, speedLimit)
right = right.coerceIn(-speedLimit, speedLimit)

倒车时要保持用户直觉：

左摇杆向左 = 车头向左
左摇杆向右 = 车头向右

如果实测方向反了，再加一个：

val reverseSteerInvert = true
9. 发给 Pico 的命令

手柄模式下 Android 每 50ms 发送一次命令。

{
  "cmd": "drive",
  "source": "gamepad",
  "enable": true,
  "gear": "D",
  "speed_level": 2,
  "steer": -0.22,
  "throttle": 0.35,
  "left": 0.28,
  "right": 0.42,
  "duration_ms": 120,
  "seq": 1024
}

字段说明：

字段	说明
cmd	drive / stop
source	gamepad
enable	RB 是否按住
gear	P/N/D/R
speed_level	G1/G2/G3
steer	-1..1
throttle	0..1
left/right	左右轮目标值
duration_ms	必须有，Pico 超时停车
seq	递增序号

Pico 端仍然要有底层安全规则：

超过 300ms 没收到有效 drive 命令 -> 停车
enable=false -> 停车
急停触发 -> 停车
蓝牙断开 -> 停车
10. Android 端安全规则

Android 端必须处理：

App 进入后台 -> 发 Stop
手柄断开 -> 发 Stop
蓝牙断开 -> 发 Stop
RB 松开 -> 发 Stop
B 按下 -> 发 Stop
急停状态 -> 禁用输出
Pico 心跳异常 -> 禁用输出

发送频率建议：

20Hz，也就是每 50ms 一次。

每条 drive 命令：

duration_ms = 120
11. 手柄断开检测

不要只靠输入事件超时，因为手柄静止时可能不持续发事件。

推荐每 1 秒扫描一次：

fun findGamepads(): List<InputDevice> {
    return InputDevice.getDeviceIds()
        .mapNotNull { InputDevice.getDevice(it) }
        .filter { device ->
            isGamepadSource(device.sources)
        }
}

断开后：

1. 发 Stop。
2. UI 显示“手柄断开，小车已停车”。
3. 禁用 GAMEPAD 输出。
12. UI 设计

驾驶页新增：

GamepadStatusCard

显示：

控制源：Xbox 手柄
状态：已连接
使能：RB 按住
档位：D
速度档：G2
左摇杆：-0.22
RT：0.35
LT：0.00

未检测到手柄：

未检测到 Xbox 手柄
请先在 Android 蓝牙设置中连接手柄

手柄断开：

手柄断开，小车已停车

调试页新增：

GamepadDebugPanel

显示：

AXIS_X
AXIS_Y
AXIS_Z
AXIS_RZ
AXIS_LTRIGGER
AXIS_RTRIGGER
AXIS_GAS
AXIS_BRAKE
BUTTON_A
BUTTON_B
BUTTON_X
BUTTON_Y
BUTTON_R1
BUTTON_L1
DPAD_UP
DPAD_DOWN
13. 和 DeepSeek / 语音 Agent 的关系

允许语音命令：

进入手柄模式
退出手柄模式
把手柄速度限制到二档
读取手柄状态
自检
停车

不允许语音命令：

忽略 RB 使能
解除急停
手柄断开也继续行驶
把倒车速度开到最高

Tool Call 示例：

{
  "name": "cart_set_control_source",
  "arguments": {
    "source": "GAMEPAD"
  }
}
{
  "name": "cart_set_gamepad_speed_level",
  "arguments": {
    "level": 2
  }
}
14. 实现优先级
P0：手柄输入调试
连接 Xbox 手柄。
App 显示按键和摇杆原始值。
不控制小车。
确认 RT/LT 在当前安卓设备上的 Axis。
P1：安全手柄模式
RB 按住使能。
RT 前进。
LT 后退。
左摇杆转向。
B 停止。
通过现有蓝牙发送给 Pico。
P2：驾驶体验优化
死区。
曲线。
速度档。
倒车限速。
缓启动。
UI 显示当前输入。
P3：语音 / Agent 联动
语音可以进入手柄模式。
语音可以限制速度档。
语音可以退出手柄模式。
语音不能绕过 RB 使能。
15. 给 Codex 的第一阶段提示词
请在当前 Android 小车控制 App 中新增 Xbox 蓝牙手柄输入调试能力。

要求：
1. 不要改动现有蓝牙通讯、DeepSeek、Vosk、AudioRecord、Pico 控制逻辑。
2. 在 Activity 层拦截 dispatchGenericMotionEvent 和 dispatchKeyEvent。
3. 判断事件是否来自 Gamepad / Joystick。
4. 新增 GamepadState，记录：
   - deviceId
   - deviceName
   - AXIS_X
   - AXIS_Y
   - AXIS_Z
   - AXIS_RZ
   - AXIS_LTRIGGER
   - AXIS_RTRIGGER
   - AXIS_GAS
   - AXIS_BRAKE
   - A/B/X/Y
   - RB/LB
   - DPAD
5. 新增 GamepadDebugPanel 页面或卡片，实时显示所有轴值和按键状态。
6. 第一阶段只显示输入，不控制小车。
7. 日志中打印手柄连接状态和关键输入变化。
16. 给 Codex 的第二阶段提示词
请继续实现 Xbox 手柄安全驾驶模式。

要求：
1. 新增 ControlSource.GAMEPAD。
2. 新增 GamepadController，负责：
   - 读取手柄状态
   - 应用死区
   - 处理按键映射
   - 生成 DriveCommand
3. 按键映射：
   - 左摇杆 X 控制转向
   - RT 控制前进油门
   - LT 控制后退油门
   - RB 必须按住才允许移动
   - 松开 RB 立即 Stop
   - B 键立即 Stop
   - Y 提高速度档
   - X 降低速度档
   - DPAD_UP 切 D
   - DPAD_DOWN 切 R
   - DPAD_LEFT 切 N
   - DPAD_RIGHT 切 P
4. 每 50ms 发送一次 drive 命令给 Pico。
5. 每条 drive 命令必须带 duration_ms=120。
6. 蓝牙断开、App 后台、手柄断开、急停、Pico 心跳异常时必须发 Stop。
7. 倒车最大速度限制到 25%。
8. G4 高速档默认不开放。
17. 验收清单

输入验收：

Xbox 手柄连接 Android 后，App 能显示设备名。
左摇杆移动时 AXIS_X 变化。
RT / LT 有可读数值。
A/B/X/Y/RB/DPAD 有状态变化。

安全验收：

不按 RB，RT/LT 无法让小车移动。
松开 RB，小车立即停车。
按 B，小车立即停车。
App 切后台，小车停车。
蓝牙断开，小车停车。
手柄断开，小车停车。
急停状态下小车不能动。

驾驶验收：

RT 前进平滑。
LT 后退限速。
左摇杆左推，车头左转。
左摇杆右推，车头右转。
速度档切换有效。
倒车速度不会过快。
18. 关键结论

Xbox 手柄模式非常适合这台小车，但它应该作为 Android App 的一个控制源，而不是直接连接 Pico。

最稳路线：

Xbox 手柄
  ↓
Android App
  ↓
GamepadController
  ↓
现有蓝牙协议
  ↓
Pico 安全底盘

核心规则：

RB 按住使能
B 立即停止
所有命令限时
Pico 永远保留看门狗和急停