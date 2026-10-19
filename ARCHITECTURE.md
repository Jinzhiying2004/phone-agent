# 架构说明

## 三通道统一（v3.00 起）

```
              ┌─────────────┐
              │  AgentLoop  │  ← 智能体基座：规划、执行、监管、记忆
              │  (基座)     │
              └──────┬──────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   ┌─────────┐ ┌─────────┐ ┌──────────┐
   │ App 内  │ │Telegram │ │ 远控 HTTP│
   │ 对话    │ │ Bot     │ │ /task    │
   └─────────┘ └─────────┘ └──────────┘
    消息通道     消息通道     消息通道
```

**设计原则**：AgentLoop 是唯一的智能体实现，三个通道只是不同的消息出入口。
任何对智能体的升级（prompt、视觉、记忆、监管）都在 AgentLoop/TaskRunner/Supervisor 里做，
三通道自动共享，无需重复实现。

## 核心模块

| 模块 | 职责 | 关键版本 |
|------|------|----------|
| AgentLoop.java | 主循环：看屏→思考→执行→监管复核；prompt 构建；JSON 解析 | v2.81 V4 去公式化，v3.09 自认知 |
| TaskRunner.java | 动作执行：tap/swipe/launch/shell/vd_* 等；root 优先策略 | v2.90 root 优先，v2.60 虚拟屏 |
| Supervisor.java | 监管者：每 N 步检查是否卡住/跑偏，打断并给理由 | v2.91 理由字段 |
| LlmClient.java | 模型调用：chat（同步）/ chatStream（SSE 真流式）/ chatVision | v3.02 SSE 流式 |
| UserMemory.java | 长期记忆：SharedPreferences 存用户偏好，prompt 注入 | v2.85 新增 |
| TaskRunLogger.java | 任务日志：task_年月日_时分秒.log，层级记录步骤/思考/监管 | v2.93 新增 |
| LocalIntentRouter.java | 本地意图路由：高频操作零 token 直达；isChat 闲聊分流 | v2.82 isChat |
| TgBot.java | Telegram 通道：轮询 + 流式编辑 + /stop + typing | v3.05 进 Service |
| AgentService.java | 前台服务：HTTP 远控 + TG Bot 保活 | v3.05 TG 搬入 |
| VirtualDisplayManager.java | 虚拟屏：后台自动化，不占用主屏 | v2.60 新增 |

## 关键架构决策

### 1. 视觉优先（v2.81）
DeepSeek V4 原生多模态，每步截图直喂主模型。删掉"自适应截图+辅助视觉模型"
双模型架构，简化为单模型。截图是第一信息源，控件列表为辅。

### 2. Root 优先（v2.90）
有 root 时 tap/swipe/back 走 `input` 命令（更稳），失败回退无障碍；
无 root 纯走无障碍。MIUI 上无障碍手势不可靠，root 是主力。

### 3. 风险分级（v2.78）
ActionRisk 四级：LOW（只读）/ MEDIUM（设备控制）/ HIGH（外部效应，默认 ASK）/
CRITICAL（破坏性）。远控/TG 进来的高风险动作无审批人时默认拦截。

### 4. 去公式化 Prompt（v2.81）
删 few-shot（V4 不需要），A-F 死流程换 3 条原则：
看明白再动手 / 独立动作可批量 / 失败换方法+自查。
规则从 9 条精简到 5 条，think 用人话写不限字数。

### 5. 目标平台
- minSdk 26（Android 8.0），targetSdk 28（绕 Android 10+ W^X，Termux 需要）
- 手工编译链（aapt2+javac+d8+apksigner），无 Gradle

## 数据流

```
用户目标
  → LocalIntentRouter（本地高频？isChat？）
  → AgentLoop.run()
    → 每步：截屏（Vision）→ 拼 prompt（含记忆/上下文/技能卡）
    → LlmClient.chat → 解析 JSON（think/state/actions/done/answer）
    → TaskRunner 执行动作
    → Supervisor.monitor() 检查（理由+建议）
    → TaskRunLogger 记录
  → answer 返回用户
```

## 任务执行流程（详细）

### 阶段一：意图识别

```
用户输入 "打开微信发消息给张三说你好"
  │
  ├─ LocalIntentRouter.tryRoute()
  │   ├─ 高频本地意图？（打开应用、打电话、发短信等）
  │   │   └─ 是 → 零 token 直接执行，返回
  │   └─ 否 → 继续
  │
  ├─ LocalIntentRouter.isChat()
  │   ├─ 纯聊天？（你好、谢谢、你是谁…）
  │   │   └─ 是 → LlmClient.chat() 轻量回复，不进任务流
  │   └─ 否 → 进入 AgentLoop
```

### 阶段二：任务规划与执行（AgentLoop.run）

```
maxSteps = 12（默认）
for step in 1..maxSteps:
  │
  ├─ 1. 感知
  │   ├─ 截屏（ScreenshotUtil：无障碍→root screencap 两级）
  │   ├─ 压缩到 720p JPEG（省 token）
  │   └─ 控件树（AccessibilityNodeInfo 剪枝）
  │
  ├─ 2. 决策
  │   ├─ buildPrompt()：拼装完整 prompt
  │   │   ├─ 截图（Vision，原生多模态）
  │   │   ├─ 用户目标 + 对话上下文
  │   │   ├─ 用户记忆（UserMemory）
  │   │   ├─ 技能卡（关键词匹配注入）
  │   │   ├─ 动作列表文档
  │   │   └─ 3 条原则 + 5 条规则
  │   ├─ LlmClient.chatVision() → JSON
  │   └─ 解析：{think, state, actions[], done, answer}
  │
  ├─ 3. 执行
  │   └─ TaskRunner：逐个执行 actions[]
  │       ├─ 有 root？→ input 命令优先
  │       └─ 无 root → 无障碍手势
  │
  ├─ 4. 监管
  │   └─ Supervisor.monitor()（每 N 步）
  │       ├─ 卡住/跑偏？→ 打断 + reason（哪步错在哪）
  │       └─ 正常 → 继续
  │
  ├─ 5. 记录
  │   └─ TaskRunLogger：步骤/思考/截图/监管 → task_*.log
  │
  └─ 6. 终止检查
      ├─ done=true → 返回 answer，结束
      ├─ 用户停止？→ 结束
      └─ 步数用完 → 结束（未完成）
```

### 阶段三：动作执行（TaskRunner）

```
action = {do: "tap_text", text: "微信"}
  │
  ├─ 风险评估（ActionRisk）
  │   ├─ LOW → 直接执行
  │   ├─ MEDIUM → 直接执行（记录）
  │   ├─ HIGH → 问用户（App 弹窗 / 远控拦截）
  │   └─ CRITICAL → 问用户（App 弹窗 / 远控拦截）
  │
  ├─ 执行通道选择
  │   ├─ tap/swipe/back：有 root？→ `input tap x y` ：无障碍手势
  │   ├─ tap_text：找节点 → 有 root？→ input : 无障碍点击
  │   ├─ launch：am start（root 可指定 --display 虚拟屏）
  │   ├─ shell：root shell 直接跑
  │   ├─ vision：截屏 → 问视觉模型拿坐标 → tap
  │   └─ vd_*：虚拟屏专用（创建/启动/点击/截图/销毁）
  │
  └─ 结果回传 → AgentLoop 继续下一步
```

## 实现路径（关键功能）

### 虚拟屏后台自动化（v2.60）
```
vd_create → VirtualDisplayManager.create()
  → DisplayManager.createVirtualDisplay(720x1280, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
  → ImageReader 截屏通道

vd_launch → root 执行 am start --display <id> <package>
vd_tap → VdInput.dex（app_process 注入）→ InputManager.injectInputEvent
vd_screenshot → ImageReader.acquireLatestImage() → 存 App cache
vd_destroy → Display.release()
```
系统相机不画到虚拟屏（MIUI 限制），普通 App 可用。

### 真流式输出（v3.02）
```
LlmClient.chatStream(prompt, callback)
  → POST /v1/chat/completions {stream: true}
  → 逐行读 SSE：data: {"choices":[{"delta":{"content":"..."}}]}
  → 每 token 调 callback.onToken(token)
  → App 内：TextView.append() 打字机效果
  → TG：editMessageText 1.5s 节流更新 + sendChatAction typing
注意：org.json 的 optString 对 JSON null 返回字符串"null"，必须 isNull 过滤（v3.04 修）
```

### 热补丁（v2.44）
```
Nuwa 式 dexElements 前插：
  → PatchManager 校验（appCode + sha256）
  → DexInjector：反射修改 PathClassLoader 的 dexElements
  → 新 dex 插到数组前面，类加载优先命中补丁
  → /patch 接口接收补丁，重启 App 生效
```

### Telegram 通道（v2.98-v3.06）
```
TgBot（跑在 AgentService 前台服务里）
  → getUpdates 长轮询（30s timeout）
  → /start 绑定主人（chatId 存 SharedPreferences）
  → 消息 → isChat？→ 轻量回复 ：AgentLoop 任务
  → 流式：editMessageText + typing 指示器
  → /stop → tgTaskStop=true → AgentLoop.isStopped() 中断
```
