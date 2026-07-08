可以。文件工具这次没成功生成附件，我先把正文给你，直接保存成：

```text
docs/ANDROID_CART_UI_VISUAL_DESIGN_GUIDE.md
```

---

# Android 小车控制 App 界面美化指导文档

## 1. 设计定位

当前 App 已经有：

```text
DeepSeek API
蓝牙通讯
语音朗读
AudioRecord + VAD + Vosk
Tool Calls
Pico 小车控制
```

所以它不应该只是一个普通按钮面板，而应该设计成：

```text
赛博小车驾驶舱
+
曼波语音 Agent 控制台
+
工程调试面板
```

核心目标：

```text
一眼知道能不能动
一眼知道车是否安全
一眼知道曼波在听、在想、在执行什么
自己用也觉得有点酷
```

---

## 2. 整体视觉风格

推荐风格：

```text
Dark Cyber Utility
深色赛博工具风
```

关键词：

```text
深色底
霓虹蓝/青色点缀
卡片化
仪表盘
状态灯
大按钮
轻微发光
清晰安全状态
```

不要做成太花的游戏 UI，也不要做成普通表单 App。它应该像一个小型机器人控制台。

---

## 3. 页面结构

建议底部 4 个 Tab：

```text
驾驶
语音
状态
调试
```

### 3.1 驾驶页

驾驶页是主界面。

应该包含：

```text
顶部状态栏：
蓝牙 / 电池 / 当前模式 / 急停

中间：
赛博方向盘
当前档位
当前速度档
转向角度

底部：
按住行驶
急停
最近一次指令
```

驾驶页要优先解决两个问题：

```text
好不好看
安不安全
```

建议布局：

```text
┌────────────────────────────┐
│ ●已连接   26.8V   手动   安全 │
├────────────────────────────┤
│                            │
│        ╭────────╮          │
│     ╭──│   D2   │──╮       │
│     │  │  方向盘 │  │       │
│     ╰──│  -12°  │──╯       │
│        ╰────────╯          │
│                            │
│       P   N   D   R        │
│                            │
│     D1   D2   D3   D4      │
│                            │
│   [      按住行驶      ]    │
│                            │
│   [ 急停 ]     最近：停止   │
└────────────────────────────┘
```

---

### 3.2 语音页

语音页不要只是聊天框，要像“曼波控制核心”。

包含：

```text
当前语音状态
麦克风能量 / 声波
VAD 状态
原始识别文本
归一化文本
DeepSeek 意图
Tool Call 执行记录
```

建议状态：

```text
未唤醒
正在监听
检测到疑似语音
已唤醒
正在识别
正在思考
正在执行
执行完成
```

推荐布局：

```text
┌────────────────────────────┐
│ 曼波 Agent                 │
├────────────────────────────┤
│          ◯                 │
│      正在监听唤醒词          │
│      “你好曼波”             │
│                            │
│  声波: ▂▃▅▆▃▂              │
│                            │
│ 原始识别：蓝波 自检          │
│ 归一化：曼波 自检            │
│ 意图：self_check            │
│                            │
│ Tool Timeline              │
│ ✓ cart_get_status          │
│ ✓ cart_read_sensors        │
└────────────────────────────┘
```

---

### 3.3 状态页

状态页用于查看小车健康情况。

用卡片，不要用一堆纯文本。

卡片包括：

```text
电池
蓝牙
Pico
急停
左右电机
传感器
DeepSeek
语音
```

示例：

```text
┌─────────────┬─────────────┐
│ 电池         │ 蓝牙         │
│ 26.8V       │ 已连接       │
│ 72%         │ 42ms        │
├─────────────┼─────────────┤
│ Pico        │ 安全         │
│ 心跳正常     │ 急停正常     │
├─────────────┼─────────────┤
│ 左轮         │ 右轮         │
│ PWM 0%      │ PWM 0%      │
└─────────────┴─────────────┘
```

---

### 3.4 调试页

调试页可以工程化，但不能丑。

包含：

```text
左轮测试
右轮测试
双轮低速测试
HX711 归零
读取传感器
发送 Stop
原始命令
日志查看
配置导入导出
```

危险操作规则：

```text
红色边框
长按触发
二次确认
执行后自动 Stop
```

---

## 4. 颜色系统

推荐深色主题。

### 4.1 基础色

```kotlin
val BgMain = Color(0xFF070A12)
val BgPanel = Color(0xFF101624)
val BgCard = Color(0xFF141C2E)
val BorderSoft = Color(0xFF263248)

val TextMain = Color(0xFFEAF0FF)
val TextSecondary = Color(0xFF8F9BB3)
```

### 4.2 功能色

```kotlin
val NeonBlue = Color(0xFF39A7FF)
val NeonCyan = Color(0xFF2EF2FF)
val NeonPurple = Color(0xFF8B5CFF)

val SafeGreen = Color(0xFF35E48B)
val WarnYellow = Color(0xFFFFC857)
val DangerRed = Color(0xFFFF3B5C)
val ReverseOrange = Color(0xFFFF7A2F)
```

### 4.3 使用规则

| 状态          | 颜色            |
| ----------- | ------------- |
| 正常连接        | SafeGreen     |
| 蓝牙连接中       | NeonBlue      |
| 语音监听        | NeonCyan      |
| DeepSeek 思考 | NeonPurple    |
| 倒车          | ReverseOrange |
| 警告          | WarnYellow    |
| 急停 / 故障     | DangerRed     |

注意：不要满屏发光。发光只给关键状态，否则会显得廉价。

---

## 5. 组件设计

建议拆这些组件：

```text
StatusPill
CyberCard
CyberSteeringWheel
GearSelector
SpeedLevelSelector
HoldToDriveButton
EmergencyStopButton
VoiceOrb
WaveformView
ToolCallTimeline
BatteryCard
BluetoothCard
PicoStatusCard
DebugLogPanel
```

组件职责：

```text
组件只负责显示
ViewModel 管状态
蓝牙/DeepSeek/语音逻辑不要写进 UI 组件里
```

---

## 6. 赛博方向盘设计

方向盘不一定真的 3D。第一版可以用 Compose 画一个高级感圆盘：

```text
外圈：渐变圆环
中圈：半透明暗色圆盘
中心：当前档位 D/N/R/P
下方：速度档 D1/D2/D3/D4
左右：转向角度刻度
```

状态变化：

| 状态   | 表现     |
| ---- | ------ |
| N 档  | 灰蓝低亮度  |
| D 档  | 蓝青发光   |
| R 档  | 橙色发光   |
| 急停   | 红色闪烁边框 |
| 蓝牙断开 | 整体灰化   |

---

## 7. 按钮设计

### 7.1 按住行驶

底部主按钮：

```text
按住行驶
松手停车
```

按住时：

```text
按钮发光增强
方向盘发光增强
显示左右轮输出
```

松手时：

```text
立即发送 Stop
UI 回到等待状态
```

### 7.2 急停按钮

急停必须永远可见。

建议样式：

```text
红色大按钮
圆形或大圆角
位置固定
不能藏在菜单里
```

急停触发后：

```text
全局红色边框
驾驶控件禁用
语音播报
需要人工解除
```

---

## 8. 语音 Agent 设计

建议做一个 `VoiceOrb` 组件。

不同状态不同表现：

| 状态    | UI           |
| ----- | ------------ |
| 未唤醒   | 暗色圆环         |
| 正在监听  | 微弱呼吸         |
| 检测到声音 | 声波跳动         |
| 已唤醒   | 圆环亮起         |
| 正在识别  | 显示识别文本       |
| 正在思考  | 紫色旋转光        |
| 正在执行  | 显示 tool call |
| 执行完成  | 绿色完成态        |

语音页显示内容：

```text
原始识别：蓝波 自检
归一化：曼波 自检
意图：self_check
工具：cart_get_status()
结果：正常
```

这样比只显示“识别成功”有用很多。

---

## 9. 动效建议

推荐轻微动效：

```text
连接成功：状态胶囊亮一下
语音监听：圆环呼吸
DeepSeek 思考：紫色光点旋转
按住行驶：方向盘增强发光
急停：红色边框闪烁
Tool Call：时间线逐条出现
```

不推荐：

```text
大面积粒子
复杂 3D
按钮乱飞
过度抖动
彩虹渐变
```

---

## 10. 安全 UX 规则

美化不能牺牲安全。

必须做到：

```text
1. 急停永远可见。
2. 蓝牙断开时禁用驾驶。
3. Pico 心跳异常时禁用驾驶。
4. D/R 切换必须经过 Stop 或 N。
5. 倒车状态必须明显。
6. 高速档不默认开启。
7. 移动类语音命令需要限时执行。
8. AI 不能解除急停。
9. 调试页不能默认显示裸 PWM。
10. 任何异常都要优先显示在顶部状态栏。
```

---

## 11. 推荐状态模型

UI 最好由统一状态驱动。

```kotlin
data class CartUiState(
    val connected: Boolean,
    val batteryVoltage: Float?,
    val batteryPercent: Int?,
    val mode: CartMode,
    val gear: Gear,
    val speedLevel: Int,
    val steerValue: Float,
    val estop: Boolean,
    val picoHeartbeatOk: Boolean,
    val voiceState: VoiceState,
    val lastCommand: String?,
    val toolCalls: List<ToolCallUiItem>
)
```

UI 不直接关心蓝牙细节，只根据状态渲染。

---

## 12. 美化优先级

### P0：统一主题

先做：

```text
深色背景
统一卡片
统一按钮
统一状态色
```

这一步最快见效。

### P1：重做驾驶页

优先做：

```text
顶部状态胶囊
赛博方向盘
档位按钮
速度档按钮
按住行驶
急停
```

这是最能体现质感的地方。

### P2：重做语音页

做：

```text
VoiceOrb
声波
识别文本
意图卡片
Tool Call 时间线
```

### P3：状态页卡片化

做：

```text
电池卡
蓝牙卡
Pico 卡
安全卡
电机卡
传感器卡
```

### P4：调试页整理

做：

```text
危险操作分组
日志过滤
按钮二次确认
配置导入导出
```

---

## 13. 给 Codex 的第一阶段提示词

```text
请基于当前 Android 小车控制 App 做 UI 美化重构。

目标风格：
Dark Cyber Utility，深色赛博驾驶舱风格，但保持工程可读性和安全优先。

要求：
1. 不要改动蓝牙、DeepSeek、Vosk、AudioRecord、Pico 控制逻辑。
2. 只重构 UI 层、主题、组件和 ViewModel 状态绑定。
3. 新增统一主题 CartTheme，包括：
   - 深色背景
   - 卡片色
   - 边框色
   - 霓虹蓝
   - 青色
   - 紫色
   - 安全绿
   - 警告黄
   - 危险红
   - 倒车橙
4. 新增通用组件：
   - StatusPill
   - CyberCard
   - EmergencyStopButton
   - HoldToDriveButton
   - GearSelector
   - SpeedLevelSelector
5. 重做驾驶页：
   - 顶部状态胶囊：蓝牙、电池、模式、急停
   - 中部赛博方向盘占位组件
   - P/N/D/R 档位
   - D1/D2/D3/D4 速度档
   - 底部按住行驶按钮和急停按钮
6. 所有控件必须根据 CartUiState 渲染。
7. 蓝牙断开、急停、Pico 心跳异常时禁用驾驶控件。
8. 不要暴露裸 PWM 控制在主驾驶页。
9. 保持现有功能可用，不要删除现有调试入口。
```

---

## 14. 给 Codex 的第二阶段提示词

```text
请继续美化语音 Agent 页面。

要求：
1. 新增 VoiceOrb 组件，根据 voiceState 显示不同发光状态。
2. 新增 WaveformView，显示当前麦克风 RMS 或 VAD 能量。
3. 展示：
   - 原始识别文本
   - 归一化文本
   - DeepSeek 意图
   - 当前执行工具
4. 新增 ToolCallTimeline，显示最近 tool call 名称、参数摘要、成功/失败状态。
5. 停车、急停等本地安全命令要在 UI 上明显标记为本地执行，不等待 DeepSeek。
6. 保持深色赛博风格，但日志文本必须清晰可读。
```

---

## 15. 给 Codex 的第三阶段提示词

```text
请整理状态页和调试页。

状态页：
1. 用卡片展示电池、蓝牙、Pico、急停、左右电机、传感器。
2. 每张卡显示一个核心指标和一个状态描述。
3. 异常状态使用黄色或红色。
4. 点击卡片可以展开详情。

调试页：
1. 分组显示电机测试、传感器校准、日志、原始命令。
2. 危险动作需要长按或二次确认。
3. 调试日志支持过滤：
   - 全部
   - 蓝牙
   - 语音
   - DeepSeek
   - Pico
   - 错误
4. 不要让调试页视觉上压过主驾驶页。
```

---

## 16. 最终判断

这次美化的核心不是“弄点颜色”，而是把 App 变成：

```text
一个有状态感的小车驾驶舱
一个有反馈感的曼波 Agent
一个不难看的工程调试工具
```

第一版最值得先做的是：

```text
驾驶页
+
语音页
```

这两个页面做好，整个项目的感觉会立刻不一样。
