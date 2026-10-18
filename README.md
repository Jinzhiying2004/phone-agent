# 手机智能体 phone-agent

低 token 消耗的 Android 自动化智能体：任务在手机端本地拆解、本地执行，
大模型只做规划与决策，配合本地意图路由、技能卡复用与历史压缩，
token 用量远低于 OpenClaw 等全云端方案。支持无障碍 + root 双通道操作、HTTP 远程控制、
视觉定位、技能卡、定时任务、Telegram 机器人与热补丁。

> 最低支持 Android 8.0（API 26），完整适配 Android 11。

## 功能

- **任务执行器**：自然语言目标 → 本地拆解 → 逐步执行 → 监管者复核闭环
- **思考执行员**：思考者规划、执行者动手、监管者验收，三权分立
- **vision 动作**：截屏后问视觉模型拿坐标，图标按钮也能点
- **HTTP 远控服务**：`/status` `/screen` `/action` `/task`（支持目标式 `goal` 模式），
  经 Tailscale 从云端直连手机操控，token 鉴权 + 来源 IP 白名单
- **热补丁**：Tinker 式 dex 补丁通道，小修复不用重装（`/patch`）
- **应用内更新**：检查更新 / 更新源配置 / 下载进度 / 调起安装
- **内置 Termux**（免 root 模式）：targetSdk 28 绕开 Android 10+ 的 W^X 限制
- **技能卡系统**：能力按需注入，省 token
- **定时任务 / 悬浮窗 / 运行记录导出 / 对话历史 / 软件自检**

远控 API 完整说明见 [API.md](API.md)，版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 连接你自己的 Muse

这套架构按"每人一套"设计：任何人从 GitHub 下了包，不改代码，按三步就能连上自己的 Muse。

**第 1 步：手机端准备**
- 装上 APK，打开「远控」服务
- 每台手机首次启动会自动生成自己的 token（只存在手机本地，谁也看不到）

**第 2 步：Muse 端准备**
- 自己的 Muse 通过 Tailscale 连接器加入**自己的** tailnet
- 手机装 Tailscale 登录同一账号，确认连接正常

**第 3 步：配对**
- 打开 App「云端操控」页，看到连接信息：IP、端口（v2.41 起默认 28789，可在 App 内修改）、token
- 把这三项告诉自己的 Muse，就能直连操控了

**安全说明**：远控服务只放行本机回环和 Tailscale 网段（100.64.0.0/10），
同一 WiFi 下其他设备连不上——Tailscale 是云端操控的唯一通道。
token 不要发给别人，谁拿到 token 就能操作你的手机。

## 构建

手工编译链（aapt2 + javac + d8 + apksigner），无需 Gradle / Android Studio。

依赖：JDK 17、Android SDK（含 `build-tools;34.0.0` 与 `platforms;android-34`）。

```bash
# 按 build.sh 头部的路径准备好 JDK 与 SDK（或按需修改）
./build.sh
# 产物：phone-agent-<版本号>.apk（out/ 目录）
```

构建脚本缺失 `debug.keystore` 时会自动生成调试签名。
`baselines/`（热补丁差分基线）每次构建自动归档，无需手动维护。
启动器图标以 base64 内嵌在 `tools/launcher_icons.b64.json`，构建时缺失自动还原。

## 版本号规则

- 每次迭代版本号 **+0.01**；单次迭代功能数超过 5 个则 **+0.1**
- `versionCode` 每次构建 +1（内部使用）
- 产物命名：`phone-agent-<版本号>.apk`
- 每次发版前必须按发版测试规范实测，无法真机验证的部分如实声明

## 反馈与建议

欢迎提 [Issue](../../issues)：Bug 请附机型 / Android 版本 / 复现步骤；
功能建议请说明使用场景。我们会定期 triage 并安排进夜间迭代。

## License

[MIT](LICENSE)
