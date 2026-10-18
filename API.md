# 远程控制 API（v2）

App 内置 HTTP 服务，端口 **28789**（v2.41 起默认端口由 18789 改为 28789，
可在 App「远控」页修改，1024–65535），所有接口需 token 鉴权：
`?token=xxx` 或 Header `X-Token: xxx`。token 在 App「远控」页可见，
可一键复制连接信息发给云端。

安全：v2.42 起服务加来源 IP 白名单，只放行本机回环与 Tailscale 网段
（100.64.0.0/10），局域网其他设备直连会被拒绝。Tailscale 断线时云端
将无法直连（无局域网备用）。

## 接口

### GET /status
```bash
curl "http://手机IP:28789/status?token=xxx"
# {"ok":true,"version":2,"accessibility":true,"service":true}
```

### GET /screen — 读屏（剪枝后的结构化节点，零截图）
```bash
curl "http://手机IP:28789/screen?token=xxx"
# {"ok":true,"nodes":[{"text":"设置","desc":"","clickable":true,"cx":540,"cy":200}, ...]}
```

### GET /screenshot — 截屏（无障碍优先，root 兜底）
```bash
curl "http://手机IP:28789/screenshot?token=xxx"
# {"ok":true,"via":"accessibility","png":"<base64>"}
curl "http://手机IP:28789/screenshot?token=xxx&compact=1"
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
curl -X POST "http://手机IP:28789/action?token=xxx" \
  -H "Content-Type: application/json" \
  -d '{"do":"tap_text","text":"显示"}'
# {"ok":true,"log":[...]}
```
支持的 do：launch / tap（按坐标点）/ tap_text（按文字点）/ tap_id（按节点 id 点）/
wait_text / assert_text / input / swipe / sleep / back /
shell（需 root）/ termux（免 root）/ search / vision（截屏后问视觉模型拿坐标）/
read_file / write_file / fetch_url / screenshot / use_card（跑技能卡）/ skill

任务步骤通用字段：
- `"after": 0.8` — 步骤成功后等待秒数（默认 0.8）
- `"optional": true` — 可选步骤：失败只跳过、不中断整个任务（v2.53+；
  `use_card` 内部的技能卡步骤同样支持）

### POST /task — 执行完整任务 JSON
```bash
curl -X POST "http://手机IP:28789/task?token=xxx" \
  -H "Content-Type: application/json" \
  -d @tasks/example_settings.json
# {"ok":true,"log":[...]}
```

#### 🎯 目标式任务（v2.45+；v2.47 起默认异步）
```bash
curl -X POST "http://手机IP:28789/task?token=xxx" \
  -H "Content-Type: application/json" \
  -d '{"goal":"打开汽水音乐领免费听歌时长","maxSteps":30}'
# {"ok":true,"started":true,"taskId":"..."}
```
- body 带 `"goal"`（且不带 `steps`）即进入目标式模式：
  手机端自主拆解子任务、试错、自恢复执行
- 需手机端已配置大模型（App「主页」→「云端大模型」），否则返回错误
- 默认**异步**：立即返回 `taskId`，用 `GET /task/status` 轮询看思考与执行过程
- 加 `"wait":true` 恢复同步老行为（等任务跑完才返回 log）
- `"maxSteps"`：最多步数，默认 30
- 任务运行时手机顶部弹悬浮窗实时推步骤/思考（v2.48+）；
  结束按铁律自动回桌面

### GET /task/status — 远控任务实时进度（v2.47+）
```bash
curl "http://手机IP:28789/task/status?taskId=xxx&token=xxx"
# {"ok":true,"taskId":"rt-1-23456","goal":"...","maxSteps":30,
#  "done":false,"resultOk":false,"step":3,"think":"...",
#  "elapsedSec":42,"log":[...最近25条...],"audit":[...]}
```
- 不带 `taskId` 则返回最近一个远控任务
- App「远控」页「远控任务」实时卡 1.5 秒刷新同一份数据

### POST /patch — 热补丁推送（v2.44+）
```bash
curl -X POST "http://手机IP:28789/patch?token=xxx" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @patch-78-xxx.zip
# {"ok":true,"tag":"xxx"}
```
- body 为补丁 zip 二进制（Nuwa 式 dex 整类替换补丁）
- 服务端校验 appCode 一致 + sha256，通过后原子落盘、延迟重启生效
- 补丁用 `tools/make_patch.py` 对基线差分打包；
  manifest/res/assets 变更不能打补丁，仍需整包安装
- ⚠️ 推送前请按铁律先问用户「推吗」，确认后再推

## 云端 Agent 接入（Tailscale）

1. 手机安装 Tailscale App，登录并加入你的 tailnet
2. 云端 Agent 执行 `tailscale up`，你在管理后台批准 `muse` 设备
3. Agent 通过隧道代理访问 `http://手机tailnetIP:28789/...`
4. 全程 token 鉴权；不要把 28789 直接暴露到公网
