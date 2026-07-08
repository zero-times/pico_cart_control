# Android DeepSeek Tool Calls Agent Guide

这份文档用于把车载安卓手机做成一个轻量 Agent Host。目标不是让 DeepSeek 直接控制小车，而是让安卓 App 管理会话、上下文、工具执行和安全边界，DeepSeek 只负责根据上下文决定下一步要调用什么工具。

## 1. 核心结论

车载运行时不需要 Mac 或小主机。推荐架构：

```text
安卓手机 App
  -> DeepSeek API
  -> Tool Calls
  -> App 本地工具执行层
  -> 蓝牙 / USB 串口
  -> Pico JSON 串口协议
  -> 电机 / HX711 / 电池 / 急停
```

DeepSeek API 支持 Tool Calls / Function Calling，但函数本身需要 App 执行。也就是说：

```text
DeepSeek 返回：我想调用 cart_move(...)
安卓 App 执行：把 cart_move 转成 Pico 串口命令
Pico 执行：安全限速、看门狗、PWM 输出
```

这套方案本质上是一个“小型 Codex / 小型 Agent Runtime”：

```text
AgentRuntime
  -> Session Manager
  -> Memory Retriever
  -> Tool Registry
  -> Tool Executor
  -> Safety Guard
  -> DeepSeek Client
```

## 2. 和 MCP 的关系

第一版不需要完整 MCP。

安卓 App 内部的 `Tool Registry + Tool Executor` 已经能实现类似 MCP 的效果：

```text
模型看到工具定义
模型返回 tool_call
App 执行工具
App 把结果返回给模型
```

后续如果要标准化，再把工具层抽象成 MCP Server。

推荐路线：

```text
V1：安卓 App 内置 Tool Calls 执行层
V2：工具定义 JSON 化
V3：抽象成本地 Cart Tool Server
V4：兼容 MCP
```

## 3. DeepSeek 官方文档映射

安卓端接入 DeepSeek API 时，优先参考这四个官方指南：

| DeepSeek 指南 | 在本项目中的用途 |
|---|---|
| [多轮对话](https://api-docs.deepseek.com/zh-cn/guides/multi_round_chat) | 说明 API 是无状态的；每次请求都要由 App 拼好历史消息 |
| [JSON Output](https://api-docs.deepseek.com/zh-cn/guides/json_mode) | 用于让模型输出结构化摘要、配置建议、诊断报告 |
| [Tool Calls](https://api-docs.deepseek.com/zh-cn/guides/tool_calls) | 用于让模型提出工具调用，App 执行工具后把结果发回模型 |
| [上下文硬盘缓存](https://api-docs.deepseek.com/zh-cn/guides/kv_cache) | 用于优化消息拼接顺序，提高重复前缀的缓存命中 |

### 3.1 多轮对话落地方式

DeepSeek `/chat/completions` 是无状态 API。服务端不会替 App 保存上下文，所以每次请求都必须由安卓 App 重新拼接：

```text
system / developer 稳定规则
  -> 长期摘要
  -> 设备能力和安全边界
  -> 当前 cart_status
  -> 检索出的相关历史
  -> 最近 N 轮对话
  -> 本轮 user 输入
```

App 必须保存：

```text
用户消息
助手消息
tool_call
tool_result
本轮 cart_status
```

Tool Calls 期间也要把模型返回的 assistant tool_call 消息和 App 执行后的 tool result 继续追加到 `messages`，再发给 DeepSeek 生成下一步回复。

### 3.2 JSON Output 落地方式

JSON Output 不建议用于实时控制小车。实时控制应该使用 Tool Calls。

JSON Output 适合这些场景：

```text
生成 config_patch
生成故障诊断报告
生成本轮调试摘要
生成长期 memory summary
生成工具调用复盘
```

使用规则：

```text
1. 请求中设置 response_format = {"type": "json_object"}。
2. system 或 user prompt 中必须明确出现 json 字样。
3. prompt 中给出期望 JSON 样例。
4. max_tokens 要留足，避免 JSON 被截断。
5. App 仍要做 JSON parse 和字段校验，不能直接信任模型输出。
```

示例用途：

```json
{
  "summary": "本轮测试左轮低速正转正常，未触发急停。",
  "config_patch": {
    "max_pwm": 0.35,
    "reverse_max_pwm": 0.22
  },
  "risks": ["右轮尚未测试", "电池电量未确认"]
}
```

### 3.3 Tool Calls 落地方式

DeepSeek Tool Calls 的关键点：

```text
模型只返回要调用的函数名和参数。
函数必须由安卓 App 自己执行。
执行结果必须作为 tool message 发回模型。
```

本项目中，Tool Calls 是核心能力：

```text
cart_get_status
cart_stop
cart_set_mode
cart_move
cart_turn
cart_run_motor_test
camera_capture_frame
app_write_config_patch
```

建议第一版使用普通 Tool Calls；后续稳定后再考虑 strict 模式。

如果使用 strict 模式：

```text
1. base_url 使用 https://api.deepseek.com/beta。
2. 每个 tool 的 function 增加 "strict": true。
3. 每个 object 的所有字段都要放入 required。
4. additionalProperties 必须为 false。
5. 只使用官方支持的 JSON Schema 子集。
```

无论是否 strict，App 层都必须校验：

```text
工具名是否允许
参数类型是否正确
duration_ms 是否超限
speed_level 是否超限
当前状态是否允许移动
是否需要用户确认
```

### 3.4 上下文硬盘缓存落地方式

DeepSeek 的上下文硬盘缓存默认开启，不需要额外代码。但 App 的消息拼接顺序会影响缓存命中。

为了提高缓存命中，应该让稳定内容尽量出现在前缀：

```text
固定 system prompt
固定 developer 安全规则
固定工具使用原则
相对稳定的硬件说明
长期 memory summary
然后才放当前状态、检索结果、最近对话、本轮用户输入
```

推荐拼接顺序：

```text
1. system：车载助手角色，尽量稳定
2. developer：安全规则，尽量稳定
3. system/name=cart_capabilities：硬件能力，变化较少
4. system/name=memory_summary：长期摘要，偶尔更新
5. system/name=cart_status：当前状态，每轮变化
6. system/name=retrieved_notes：检索上下文，每轮变化
7. recent messages：最近对话，每轮增长
8. user：本轮输入
```

不要把变化很大的内容放到最前面，否则容易破坏公共前缀。

返回的 `usage` 中可以记录：

```text
prompt_cache_hit_tokens
prompt_cache_miss_tokens
```

这两个值可写入日志，用来观察上下文拼接策略是否有效。

## 4. Android Agent Host 架构

```text
Android App
  ├─ DeepSeekClient
  ├─ SessionManager
  ├─ MemoryStore
  ├─ MemoryRetriever
  ├─ ToolRegistry
  ├─ ToolExecutor
  ├─ SafetyGuard
  ├─ CartSerialClient
  ├─ CameraService
  └─ VoiceService
```

| 模块 | 作用 |
|---|---|
| `DeepSeekClient` | 调用 DeepSeek Chat Completion / Tool Calls API |
| `SessionManager` | 管理当前会话、最近消息、会话标题 |
| `MemoryStore` | 存储历史对话、摘要、设备配置、状态 |
| `MemoryRetriever` | 从历史中检索相关上下文 |
| `ToolRegistry` | 定义所有可用工具及 JSON Schema |
| `ToolExecutor` | 执行工具调用，如控制小车、拍照、读状态 |
| `SafetyGuard` | 在工具执行前做安全检查 |
| `CartSerialClient` | 通过蓝牙/USB 串口和 Pico 通讯 |
| `CameraService` | 拍照、预览、图像压缩 |
| `VoiceService` | 语音输入和状态播报 |

## 5. 每次请求 DeepSeek 时发送什么

每次 API 请求不要只发用户一句话，而是组装一个轻量上下文包。

推荐内容：

```text
1. system：角色定义
2. developer：安全规则和工具边界
3. memory：长期摘要、设备能力、当前配置
4. retrieved_notes：从历史索引中检索到的相关片段
5. recent_messages：最近 10~20 轮对话
6. cart_status：当前电池、急停、模式、传感器状态
7. tools：当前可用工具定义
8. user：本次用户输入
```

为了配合上下文硬盘缓存，前 1~3 项尽量保持稳定；变化大的 `cart_status`、`retrieved_notes`、最近对话放在后面。

示例：

```json
[
  {
    "role": "system",
    "content": "你是轻便取快递牵引小车的车载助手，负责理解用户意图、调用安全工具、解释状态。"
  },
  {
    "role": "developer",
    "content": "不能调用裸 PWM，不能解除物理急停，所有移动必须短时限速，遇到异常先 stop。"
  },
  {
    "role": "system",
    "name": "cart_status",
    "content": "{\"mode\":\"idle\",\"battery_v\":25.8,\"estop\":false,\"fault\":null}"
  },
  {
    "role": "user",
    "content": "进入调试模式，低速测试左轮"
  }
]
```

## 6. 存储设计

第一版建议直接用 SQLite / Room，不需要向量数据库。

| 数据 | 存储 | 用途 |
|---|---|---|
| 最近消息 | SQLite `messages` 表 | 拼接最近对话上下文 |
| 长期摘要 | SQLite `session_summary` 表 | 压缩长期上下文 |
| 历史索引 | SQLite FTS5 | 按关键词检索相关历史 |
| 设备配置 | JSON / SQLite | GPIO、限速、传感器参数 |
| 设备状态 | 内存 + SQLite | 电池、急停、模式、错误 |
| 工具定义 | App 内置 JSON | 每次发给 DeepSeek |
| 工具日志 | SQLite `tool_calls` 表 | 调试和复盘 |

### 推荐表结构

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  title TEXT,
  summary TEXT,
  created_at INTEGER,
  updated_at INTEGER
);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  session_id TEXT,
  role TEXT,
  content TEXT,
  created_at INTEGER
);

CREATE TABLE tool_calls (
  id TEXT PRIMARY KEY,
  session_id TEXT,
  tool_name TEXT,
  arguments_json TEXT,
  result_json TEXT,
  ok INTEGER,
  error TEXT,
  created_at INTEGER
);

CREATE TABLE cart_status_log (
  id TEXT PRIMARY KEY,
  battery_v REAL,
  estop INTEGER,
  mode TEXT,
  fault TEXT,
  status_json TEXT,
  created_at INTEGER
);
```

FTS5 可后续加：

```sql
CREATE VIRTUAL TABLE message_fts USING fts5(
  content,
  session_id UNINDEXED,
  message_id UNINDEXED
);
```

## 7. 上下文拼接策略

第一版推荐：

```text
长期摘要：1 条
最近消息：10~20 条
检索结果：3~5 条
当前设备状态：1 条
工具定义：当前模式下可用工具
```

不要把所有历史都塞给 DeepSeek。上下文越短，响应越稳，成本也低。

### 何时更新摘要

满足任一条件就更新：

```text
当前会话超过 30 条消息
用户说“记住这个配置”
用户完成一次调试流程
工具配置发生变化
```

摘要示例：

```text
用户正在制作 24V 牵引小车，底层为 Pico + WHEELTEC D50A + 双 HX711。
安全规则：Pico 保留 300ms 看门狗，不允许 AI 控制裸 PWM。
当前目标：安卓 App 通过 DeepSeek Tool Calls 控制 Pico 调试和读取状态。
```

## 8. Tool Registry 设计

工具分三类：

```text
cart_*：小车控制与状态
camera_*：摄像头和图像
app_*：本地配置、日志、会话
```

### 推荐工具列表

| 工具 | 作用 |
|---|---|
| `cart_get_status` | 读取电池、急停、模式、传感器、电机状态 |
| `cart_stop` | 立即停车 |
| `cart_set_mode` | 切换 `manual/debug/tow/assist` |
| `cart_move` | 短时低速前进/后退 |
| `cart_turn` | 短时低速左/右转 |
| `cart_set_speed_limit` | 设置最高 PWM 限制 |
| `cart_run_motor_test` | 安全测试左/右电机 |
| `cart_read_sensors` | 读取 HX711、电池等原始值 |
| `cart_tare_load_cells` | 拉力传感器归零 |
| `camera_capture_frame` | 拍一张图供模型分析 |
| `app_read_config` | 读取当前配置 |
| `app_write_config_patch` | 写配置补丁，需要安全校验 |
| `app_get_recent_logs` | 获取最近工具和错误日志 |

明确不要提供：

```text
set_left_pwm
set_right_pwm
raw_gpio_write
disable_estop
raw_serial_write
```

## 9. 工具 Schema 示例

### cart_move

```json
{
  "type": "function",
  "function": {
    "name": "cart_move",
    "description": "Move the cart for a short, limited duration. The Pico still applies speed limits and watchdog safety.",
    "parameters": {
      "type": "object",
      "properties": {
        "direction": {
          "type": "string",
          "enum": ["forward", "reverse"]
        },
        "speed_level": {
          "type": "integer",
          "minimum": 1,
          "maximum": 3
        },
        "duration_ms": {
          "type": "integer",
          "minimum": 100,
          "maximum": 800
        }
      },
      "required": ["direction", "speed_level", "duration_ms"],
      "additionalProperties": false
    }
  }
}
```

### cart_set_speed_limit

```json
{
  "type": "function",
  "function": {
    "name": "cart_set_speed_limit",
    "description": "Set a safe maximum PWM limit for the cart.",
    "parameters": {
      "type": "object",
      "properties": {
        "max_pwm": {
          "type": "number",
          "minimum": 0.1,
          "maximum": 0.65
        }
      },
      "required": ["max_pwm"],
      "additionalProperties": false
    }
  }
}
```

## 10. Tool Call 执行循环

流程：

```text
用户输入
  -> App 拼接上下文
  -> 调 DeepSeek API
  -> 如果返回普通文本：展示给用户
  -> 如果返回 tool_call：SafetyGuard 检查
  -> ToolExecutor 执行
  -> 保存 tool_call 日志
  -> 把 tool_result 继续发给 DeepSeek
  -> DeepSeek 输出总结或下一步 tool_call
```

伪代码：

```kotlin
suspend fun runAgentTurn(userText: String) {
    sessionStore.addUserMessage(userText)

    var messages = contextBuilder.build(
        sessionId = currentSessionId,
        userText = userText,
        cartStatus = cartClient.getCachedStatus()
    )

    repeat(MAX_TOOL_ROUNDS) {
        val response = deepSeekClient.chat(
            messages = messages,
            tools = toolRegistry.currentTools(),
            responseFormat = null
        )

        if (response.toolCalls.isEmpty()) {
            sessionStore.addAssistantMessage(response.text)
            ui.showAssistantText(response.text)
            return
        }

        for (call in response.toolCalls) {
            val safe = safetyGuard.validate(call)
            val result = if (safe.ok) {
                toolExecutor.execute(call)
            } else {
                ToolResult.error(safe.reason)
            }

            toolLogStore.save(call, result)
            messages = messages + response.asAssistantMessage() + result.asToolMessage(call.id)
        }
    }

    cartClient.stop()
    ui.showAssistantText("工具调用轮次过多，已停车并停止本轮操作。")
}
```

注意：

```text
实时控制路径使用 Tool Calls，不使用 JSON Output。
需要结构化摘要/配置建议时，另开一次 JSON Output 请求。
```

## 11. SafetyGuard 规则

SafetyGuard 在安卓 App 层做第一道保护，Pico 仍做最终保护。

安卓层规则：

```text
1. 急停状态下拒绝所有移动工具。
2. fault 状态下只允许 get_status、stop、read_logs。
3. cart_move / cart_turn 必须有 duration_ms。
4. duration_ms 超过上限直接拒绝。
5. reverse 速度强制降低。
6. 用户未解锁时拒绝移动。
7. 工具调用超过 MAX_TOOL_ROUNDS 后停车。
8. 需要写配置时，先让用户确认。
```

Pico 层仍必须保留：

```text
300ms 看门狗
动作限时
缓启动/缓停止
倒车限速
急停锁定
传感器异常停车
不支持裸 PWM
```

## 12. 用户体验设计

App 可以有三个入口：

```text
1. 驾驶模式：赛博方向盘 + 挡位 + 急停
2. AI 助手：对话 + 工具调用日志 + 状态卡片
3. 调试模式：电机、HX711、电池、日志
```

AI 助手对话示例：

```text
用户：进入调试模式，低速测试左轮
助手：我会先读取状态，确认急停未触发，然后切换调试模式并测试左轮。
工具：cart_get_status
工具：cart_set_mode(debug)
工具：cart_run_motor_test(left, speed_level=1)
助手：左轮测试完成，当前没有故障。是否继续测试右轮？
```

## 13. 开发优先级

### V1：能聊、能查、能安全执行简单工具

```text
SessionManager
DeepSeekClient
ToolRegistry
ToolExecutor
cart_get_status
cart_stop
cart_set_mode
cart_move
cart_turn
SafetyGuard
SQLite messages/tool_calls
```

### V2：更像轻量 Codex

```text
SQLite FTS5 检索
长期摘要
配置读写
工具调用日志复盘
相机拍照分析
语音输入和播报
```

### V3：兼容 MCP

```text
工具定义独立 JSON
ToolExecutor 抽象接口
可选暴露 HTTP API
可选 MCP Server 适配层
```

## 14. 给 Codex 的实现提示词

后续可以直接对 Codex 说：

```text
请阅读：
/workspace/pico_cart_control/ANDROID_DEEPSEEK_TOOL_CALL_AGENT_GUIDE.md
/workspace/pico_cart_control/PICO_CART_AI_MCP_IMPLEMENTATION_GUIDE.md
/workspace/pico_cart_control/CODEX_LOCAL_DEBUG_GUIDE.md

我要实现车载安卓 App 的轻量 Agent Host。
目标是让安卓 App 调 DeepSeek Tool Calls，并由 App 本地执行工具，再通过蓝牙/USB 串口控制 Pico。

请先实现 V1 架构：
1. SessionManager：保存最近对话。
2. DeepSeekClient：支持 tools 参数和 tool_call 循环。
3. ToolRegistry：定义 cart_get_status / cart_stop / cart_set_mode / cart_move / cart_turn。
4. ToolExecutor：把工具调用转成 Pico JSON 串口命令。
5. SafetyGuard：拒绝裸 PWM、急停状态移动、超长 duration。
6. SQLite：保存 messages 和 tool_calls。
7. 不实现完整 MCP，先做 App 内置 Tool Calls 执行层。
8. Pico 仍保留 300ms 看门狗和所有底层安全规则。
```
