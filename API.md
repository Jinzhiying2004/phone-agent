# 远程控制 API（v2）

App 内置 HTTP 服务，端口 **18789**，所有接口需 token 鉴权：
`?token=xxx` 或 Header `X-Token: xxx`。token 在 App 主界面可见。

## 接口

### GET /status
```bash
curl "http://手机IP:18789/status?token=xxx"
# {"ok":true,"version":2,"accessibility":true,"service":true}
```

### GET /screen — 读屏（剪枝后的结构化节点，零截图）
```bash
curl "http://手机IP:18789/screen?token=xxx"
# {"ok":true,"nodes":[{"text":"设置","desc":"","clickable":true,"cx":540,"cy":200}, ...]}
```

### GET /screenshot — 截屏（无障碍优先，root 兜底）
```bash
curl "http://手机IP:18789/screenshot?token=<redacted>"
# {"ok":true,"via":"accessibility","png":"<base64>"}
curl "http://手机IP:18789/screenshot?token=<redacted>&compact=1"
# {"ok":true,"via":"accessibility","compact":true,"jpg":"<base64>"}
# compact=1：降到 720p + JPEG 压缩，给 LLM 看屏省 token/流量
```

### 动作风险审批（v2.15+）

所有动作按风险四级（🟢只读 / 🟡设备控制 / 🟠外部效应 / 🔴破坏性）在代码里固定分级，
App「智能体」页「🛡 动作审批」可设每级的策略：自动允许 / 每次确认 / 拒绝。
**远程（/action、/task、Telegram）进来的任务没有审批人：「每次确认」档的动作会被直接拦截**，
日志里可见 `⛔ 远程任务无审批人，拦截…`，请改用低风险动作或在手机上执行。

### POST /action — 单步执行（do 与任务步骤同名）
```bash
curl -X POST "http://手机IP:18789/action?token=xxx" \
  -H "Content-Type: application/json" \
  -d '{"do":"tap_text","text":"显示"}'
# {"ok":true,"log":[...]}
```
支持的 do：launch / tap / tap_text / wait_text / assert_text /
input / swipe / sleep / back / shell（需 root）

### POST /task — 执行完整任务 JSON
```bash
curl -X POST "http://手机IP:18789/task?token=xxx" \
  -H "Content-Type: application/json" \
  -d @tasks/example_settings.json
# {"ok":true,"log":[...]}
```

## 云端 Agent 接入（Tailscale）

1. 手机安装 Tailscale App，登录并加入你的 tailnet
2. 云端 Agent 执行 `tailscale up`，你在管理后台批准 `muse` 设备
3. Agent 通过隧道代理访问 `http://手机tailnetIP:18789/...`
4. 全程 token 鉴权；不要把 18789 直接暴露到公网
