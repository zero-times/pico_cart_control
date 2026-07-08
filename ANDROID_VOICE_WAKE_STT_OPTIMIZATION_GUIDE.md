# Android Voice Wake / STT Optimization Guide

这份文档用于优化车载安卓 App 的语音链路。当前状态：

```text
已实现：
Android App
  -> DeepSeek API
  -> 蓝牙通讯函数
  -> 语音朗读 TTS
  -> AudioRecord
  -> 本地 VAD
  -> 本地 Vosk STT

当前问题：
1. 唤醒词“曼波”容易识别成慢步、兰博、慢不、蓝波等。
2. “语音输入自检”等短命令识别不够稳定。
3. 唤醒词、命令识别、自然语言理解混在同一条 STT 流里，误差被放大。
```

核心结论：

```text
唤醒词不要用自由 STT 硬识别。
车控命令不要直接用开放听写。
唤醒、命令、自然语言理解要拆成三层。
```

## 1. 推荐总体链路

```text
常驻监听
  -> 本地 VAD
  -> 本地唤醒词检测
  -> 唤醒成功
  -> 播放提示音 / UI 亮起
  -> 短时间命令录音
  -> 命令 STT / 受限 grammar
  -> DeepSeek 意图理解
  -> SafetyGuard 校验
  -> ToolExecutor 执行
  -> TTS 播报结果
```

对应模块：

```text
VoiceWakeEngine
  -> VadEngine
  -> WakeWordDetector
  -> CommandRecognizer
  -> IntentResolver
  -> SafetyGuard
  -> ToolExecutor
  -> TtsSpeaker
```

## 2. 唤醒词选择

不建议单独使用：

```text
曼波
```

原因：

```text
1. 只有两个音节，抗噪声能力弱。
2. man bo / man bu / lan bo / nan bo 很容易混。
3. 日常语音里可能出现相近发音。
4. Vosk 中文小模型对短词稳定性有限。
```

推荐改成三到四个音节：

| 唤醒词 | 推荐度 | 说明 |
|---|---:|---|
| 你好曼波 | 最高 | 自然，音节更多，误唤醒少 |
| 曼波小车 | 高 | 和项目语义强相关 |
| 小车曼波 | 中 | 也可以，但不如“你好曼波”自然 |
| 曼波曼波 | 中 | 重复词有利于识别，但有点别扭 |
| 曼波 | 低 | 太短，不推荐继续单独用 |

最终建议：

```text
主唤醒词：你好曼波
备用唤醒词：曼波小车
```

不要再无限枚举：

```text
曼波、慢步、兰博、慢不、蓝波、南波...
```

这些应该交给拼音模糊匹配层统一处理。

## 3. 唤醒词检测策略

唤醒阶段不要使用开放 STT。推荐两种方案：

### 方案 A：继续用 Vosk，但使用受限 grammar

唤醒阶段 grammar：

```json
[
  "你好曼波",
  "曼波小车",
  "曼波曼波",
  "[unk]"
]
```

不要直接用识别文本等于某个词，而是做评分：

```text
文本匹配分
+ 拼音匹配分
+ 时间窗口重复命中分
- 噪声/低音量惩罚
= wake_score
```

触发条件建议：

```text
wake_score >= 0.75
或 1.5 秒内连续两次 wake_score >= 0.55
```

### 方案 B：专门上 KWS/关键词检测

如果后面要更稳，可以使用专门的 Keyword Spotting 方案，而不是 STT。

候选方向：

```text
Porcupine / Picovoice
TensorFlow Lite 自训 KWS
Vosk KWS/受限识别
Android 系统短语识别能力
```

V1 先不必上训练模型，先用 Vosk grammar + 拼音模糊匹配。

## 4. 拼音模糊匹配

把 STT 结果转成拼音后再判断。

目标唤醒词：

```text
你好曼波 -> ni hao man bo
曼波小车 -> man bo xiao che
```

相近音允许：

```text
man: man / nan / lan
bo: bo / bu / po
xiao: xiao / shao
che: che / ce / ze
```

示例：

```text
蓝波小车 -> lan bo xiao che -> 接近 man bo xiao che
慢步小车 -> man bu xiao che -> 接近 man bo xiao che
你好兰博 -> ni hao lan bo -> 接近 ni hao man bo
```

不要把这些都写成独立唤醒词，而是统一归一到相似度。

伪代码：

```kotlin
data class WakeCandidate(
    val phrase: String,
    val pinyin: List<String>,
    val threshold: Double
)

fun wakeScore(text: String): Double {
    val normalized = normalizeChinese(text)
    val pinyin = toPinyin(normalized)

    val textScore = maxTextSimilarity(normalized, wakePhrases)
    val pinyinScore = maxPinyinSimilarity(pinyin, wakePinyinPatterns)
    val volumeScore = currentVadConfidence()

    return textScore * 0.35 + pinyinScore * 0.50 + volumeScore * 0.15
}
```

## 5. VAD 参数建议

很多识别偏差不是 STT 本身造成的，而是 VAD 把开头或结尾切掉了。

建议参数：

| 参数 | 建议值 | 说明 |
|---|---:|---|
| 采样率 | 16000 Hz | Vosk 常用 |
| 单帧长度 | 20ms / 30ms | 适合 VAD |
| 唤醒前预录 | 300~500ms | 避免切掉第一个字 |
| 命令结束静音 | 700~1000ms | 避免切掉最后一个字 |
| 最短有效语音 | 500ms | 太短通常是噪声 |
| 单次命令最长 | 5~8s | 超时自动结束 |
| 唤醒冷却 | 1.5~3s | 避免重复触发 |

推荐流程：

```text
常驻监听环形缓冲 1 秒
检测到疑似唤醒
  -> 把前 500ms 音频一起送入识别
唤醒成功
  -> 播放提示音
  -> 清空命令缓冲
  -> 开始录命令
```

注意：

```text
播放 TTS 或提示音时，要暂停唤醒检测或做回声抑制。
否则 App 自己播出的“你好曼波”可能把自己唤醒。
```

## 6. 命令识别不要完全自由听写

车控命令是有限集合，V1 应使用受限命令 grammar。

推荐命令集合：

```text
停车
急停
前进
后退
左转
右转
读电量
进入调试模式
退出调试模式
测试左轮
测试右轮
开始牵引
停止牵引
自检
确认
取消
```

Vosk 命令 grammar 示例：

```json
[
  "停车",
  "急停",
  "前进",
  "后退",
  "左转",
  "右转",
  "读电量",
  "进入调试模式",
  "退出调试模式",
  "测试左轮",
  "测试右轮",
  "开始牵引",
  "停止牵引",
  "自检",
  "确认",
  "取消",
  "[unk]"
]
```

识别后不要直接执行，先转成 intent：

```json
{
  "intent": "self_check",
  "confidence": 0.86,
  "raw_text": "自检",
  "need_confirm": false
}
```

## 7. 命令分级

不同命令走不同路径。

| 命令类型 | 示例 | 是否经过 DeepSeek | 说明 |
|---|---|---|---|
| 本地安全命令 | 停车、急停 | 否 | 立即本地执行 |
| 简单状态命令 | 读电量、自检 | 可选 | 本地执行后可交给 DeepSeek 总结 |
| 调试命令 | 测试左轮、测试右轮 | 是 | DeepSeek 可解释流程，但 SafetyGuard 必须检查 |
| 自然语言命令 | 帮我检查为什么左轮不动 | 是 | DeepSeek 做意图理解 |
| 高风险命令 | 前进、后退、写配置 | 是 + 确认 | 必须二次确认或限时执行 |

本地优先命令：

```text
急停
停车
取消
```

这些不能等 DeepSeek 返回。

## 8. DeepSeek 意图理解

STT 结果不稳定时，不要只传一个文本，可以传多个候选：

```json
{
  "raw_candidates": [
    {"text": "蓝波 自检", "score": 0.62},
    {"text": "兰博 自检", "score": 0.58},
    {"text": "曼波 自检", "score": 0.55}
  ],
  "wake_phrase": "你好曼波",
  "cart_status": {
    "mode": "idle",
    "estop": false,
    "fault": null
  },
  "allowed_intents": [
    "self_check",
    "read_battery",
    "stop",
    "enter_debug"
  ]
}
```

要求 DeepSeek 输出 JSON：

```json
{
  "intent": "self_check",
  "confidence": 0.86,
  "need_confirm": false,
  "spoken_reply": "好的，我开始自检。"
}
```

注意：

```text
DeepSeek 用于意图理解，不用于唤醒词检测。
唤醒必须本地完成。
```

## 9. Android SpeechRecognizer 的位置

Android 系统 `SpeechRecognizer` 可以作为“唤醒后短句识别”的备选，而不是常驻监听。

原因：

```text
1. 官方文档说明普通实现可能会把音频发往远程服务。
2. 不适合连续识别，连续识别会消耗电量和网络。
3. on-device recognition 需要 Android 12/API 31+ 并检查设备是否支持。
```

推荐策略：

```text
常驻唤醒：Vosk grammar / KWS
短命令：Vosk command grammar
自然语言：优先 Android SpeechRecognizer；失败再用 Vosk 自由识别
```

实现时检查：

```kotlin
SpeechRecognizer.isRecognitionAvailable(context)
SpeechRecognizer.isOnDeviceRecognitionAvailable(context) // API 31+
```

## 10. 推荐状态机

```text
IDLE_LISTENING
  -> WAKE_CANDIDATE
  -> WAKE_CONFIRMED
  -> COMMAND_RECORDING
  -> COMMAND_RECOGNIZING
  -> INTENT_RESOLVING
  -> CONFIRMING optional
  -> EXECUTING_TOOL
  -> SPEAKING_RESULT
  -> IDLE_LISTENING
```

异常路径：

```text
任意状态听到“停车/急停”
  -> LOCAL_STOP
  -> SPEAKING_RESULT
  -> IDLE_LISTENING

命令超时
  -> SPEAKING_RESULT: 我没听清
  -> IDLE_LISTENING

识别置信度低
  -> CONFIRMING: 你是要自检吗？
```

## 11. 交互建议

唤醒成功后不要静默，给用户明确反馈：

```text
提示音：叮
UI：麦克风图标亮起
TTS：我在
```

命令识别失败时：

```text
我没听清，可以说：自检、读电量、停车。
```

低置信度时：

```text
你是要我开始自检吗？
```

执行高风险动作前：

```text
即将低速前进 0.5 秒，请说确认。
```

## 12. 调试日志

必须记录语音链路日志，否则很难调。

建议字段：

```json
{
  "timestamp": 123456789,
  "state": "COMMAND_RECOGNIZING",
  "vad_confidence": 0.82,
  "wake_score": 0.78,
  "raw_text": "兰博 自检",
  "normalized_text": "曼波 自检",
  "pinyin_score": 0.91,
  "intent": "self_check",
  "intent_confidence": 0.86,
  "tool_called": "cart_get_status",
  "result": "ok"
}
```

至少在调试页面显示：

```text
当前状态
最近一次 STT 文本
唤醒分数
命令 intent
DeepSeek 返回
执行的工具
```

## 13. 下一版实现优先级

### P0：立刻改

```text
1. 唤醒词从“曼波”改成“你好曼波”。
2. 唤醒阶段使用 Vosk grammar，不用自由识别。
3. 命令阶段使用命令 grammar。
4. 停车/急停本地直接执行。
5. 加 300~500ms pre-roll，避免切掉开头。
```

### P1：增强稳定性

```text
1. 加拼音模糊匹配。
2. 加 wake_score。
3. 低置信度命令走确认。
4. STT 多候选交给 DeepSeek 做意图判断。
5. 加完整语音调试日志。
```

### P2：更自然

```text
1. 唤醒后自然语言走 Android SpeechRecognizer。
2. DeepSeek JSON Output 输出结构化 intent。
3. 增加语音确认流程。
4. 增加用户自定义唤醒词。
```

## 14. 给 Codex 的实现提示词

后续可以直接对 Codex 说：

```text
请阅读：
/workspace/pico_cart_control/ANDROID_VOICE_WAKE_STT_OPTIMIZATION_GUIDE.md
/workspace/pico_cart_control/ANDROID_DEEPSEEK_TOOL_CALL_AGENT_GUIDE.md

当前安卓 App 已实现 AudioRecord、本地 VAD、Vosk STT、DeepSeek API、蓝牙通讯和 TTS。
现在要优化语音唤醒和命令识别。

请实现 V1 优化：
1. 把唤醒词从“曼波”改为“你好曼波”，备用“曼波小车”。
2. 唤醒阶段使用 Vosk grammar：你好曼波、曼波小车、曼波曼波、[unk]。
3. 命令阶段使用受限 grammar：停车、急停、读电量、自检、进入调试模式、测试左轮、测试右轮、开始牵引、停止牵引、确认、取消。
4. 新增 WakeWordDetector，支持 wake_score。
5. 新增 300~500ms pre-roll。
6. 停车、急停、取消必须本地直接执行，不等待 DeepSeek。
7. 低置信度命令进入确认状态。
8. 增加语音调试日志：raw_text、wake_score、intent、tool_called。
9. 不要让 DeepSeek 负责唤醒词检测。
```

