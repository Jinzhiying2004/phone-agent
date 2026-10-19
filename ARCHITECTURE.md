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
