# ClawMobile Mobile Runtime Progress

最后更新：2026-03-18

这份文档是工程记录，不是用户安装指南。目标是回答四个问题：

1. 我们现在到底在做什么。
2. 为什么要这么做。
3. 已经试过哪些路线，分别卡在哪里。
4. 当前正在推进的路线是什么，下一步是什么。

## 1. 当前目标

当前目标不是“把 Termux 改个皮肤”，而是要把这个 app 变成一个可复现的一键安装入口，让它在手机本机完成以下事情：

1. 从干净安装开始启动。
2. 自动准备 Termux 环境。
3. 自动完成 Ubuntu rootfs 和 Ubuntu bootstrap。
4. 自动安装并启动 OpenClaw mobile runtime。
5. 最终让 OpenClaw 能在当前这台手机上执行多步 UI 操作。

更准确地说，当前阶段的目标是先把“一键安装链路”打通到 OpenClaw 可启动；后续再逐步移除不必要的重依赖。

## 2. 为什么这个问题难

这里不是普通 Linux 机器，问题叠在一起：

- Android 上的 Termux/proot 和常规 Linux 不一样。
- 同一台手机既是安装目标，又是未来的控制对象。
- Termux、Ubuntu、OpenClaw、mobile plugin、UI agent 这几层会互相放大问题。
- 手机上的网络环境不是稳定服务器网络，实际遇到过 DNS 劫持、镜像漂移、`PARTIAL_CONNECTIVITY`。
- 从 app 进程里启动 shell/proot，和从 `adb shell run-as` 或 Termux session 启动，行为不完全一样。

所以这个项目真正难的地方不是“写一个安装脚本”，而是让整条链在 Android 的约束下可复现。

## 3. 当前架构结论

到目前为止，方向已经比最初清晰很多：

- **Droidrun 不是必须**。我们已经在 repo 内实现了更薄的 ADB + `uiautomator dump` 路线，替代了 Droidrun 的重依赖。
- **Ubuntu 目前仍在整体链路里**。不是因为 Droidrun 必须跑在 Ubuntu，而是 OpenClaw/gateway 这一层当前还留在 Ubuntu 里。
- **一键安装要分阶段**。
  - Phase 1：Termux prerequisites + Ubuntu rootfs。
  - Phase 2：Ubuntu bootstrap + OpenClaw 安装。
- **Phase 2 不能再由当前 UI 进程直接起**。它已经被拆到独立 Android 进程里执行。
- **单纯改 Termux hosts 不足以修复某些网络环境**。因此现在引入了 app 内本地代理来接管安装期网络出站。

## 4. 已尝试路线

### 路线 A：最初的 Ubuntu + Droidrun 方案

原始思路是：

- Termux 安装基础环境
- Ubuntu 里安装 OpenClaw
- Droidrun 提供 mobile multi-step agent 能力

这条路线最早暴露的问题很多：

- `proot-distro install ubuntu` 在设备上触发 locale/dpkg 相关失败。
- `dpkg-reconfigure locales` / `Function not implemented`
- `PROOT_NO_SECCOMP` 相关问题
- Ubuntu bootstrap 在 app 进程内启动时，出现 `getcwd` / `setresuid` 类错误
- Droidrun 自己还带来 Python、Rust、wheel、Portal、ADB 可见性等额外复杂度

结论：

- 可以局部修补，但整体太脆。
- 后来这条路线被拆解，保留 Ubuntu/OpenClaw，逐步移除 Droidrun。

### 路线 B：尝试直接在 Termux 里安装 Droidrun

我们认真试过把 Droidrun 直接装到 Android/Termux，而不是 Ubuntu。

看到的问题包括：

- 早期是 Termux 包源和 DNS 问题，导致连 Python 都装不稳。
- 后来 Python 装上了，但 `pip install droidrun` 又遇到 Android wheel / `pydantic-core` / Rust toolchain 这类问题。
- 即使继续硬推，也只是把问题从 Ubuntu 迁移成 Python/Rust/Android packaging 问题。

结论：

- “Termux 直装 Droidrun”理论上可能，但不值得作为主路线继续投入。
- 如果真正需求只是 mobile multi-step agent，那应该自己做薄层，而不是继续背 Droidrun 的成本。

### 路线 C：直接去掉 Droidrun，改成 repo 内自带薄 agent

这条路线已经落地：

- 保留 ADB / `uiautomator dump` / screenshot / tap / type / swipe 这类基础能力。
- 在 repo 内实现更薄的 UI 解析和 agent loop。
- 让 `android_ui_*` / `android_agent_task` 不再依赖 Droidrun Python runtime。

这是当前方向里最重要的一次收敛。

结论：

- Droidrun 的“厚抽象”已经不再是主路径。
- 以后如果要完全去掉 Ubuntu，也应该建立在这条薄层路线之上。

### 路线 D：Phase 2 仍由 app 当前进程直接执行

这条路线后来证明不可靠。

现象：

- 同一份 Ubuntu bootstrap 脚本，从 `adb shell run-as com.termux ...` 启动能跑。
- 从 app 当前 UI 进程里用 `ProcessBuilder` / `AppShell` / `TerminalSession` 直接起，反而失败。

这说明问题不在脚本本身，而在执行模型。

结论：

- 已经改成独立 bootstrap service/独立 Android 进程。
- 这一改动是保留一键安装的前提。

### 路线 E：只靠 app 侧预解析 hosts 修复网络

这一步也做过，而且是必要但不充分。

做法：

- app 启动时先用 Android 侧网络能力解析关键域名
- 把结果写入 `ClawMobile/.clawmobile/resolved-hosts`
- 同时写入 Termux 的 `usr/etc/hosts`

作用：

- 解决了系统 DNS 返回假地址 `198.18.0.x` 的一类问题

但后来发现：

- 在这台设备的实际网络上，Termux 的 `apt`/底层连接并不总是尊重我们写进去的 hosts
- 即使 `resolved-hosts` 正确，`apt` 仍可能实际去连被劫持的地址

结论：

- 仅靠 hosts 注入不够
- 必须继续往“app 内接管网络出站”走

### 路线 F：app 内本地网络代理

这是当前正在推进的路线。

核心思路：

- 在 app 内启动一个本地 HTTP/HTTPS 代理
- 代理使用 app 侧自定义解析，绕开设备当前 Wi-Fi 给出的坏 DNS
- Phase 1 的 `apt` 和 Phase 2 的 Ubuntu `apt`/`curl` 统一走这个本地代理

这一步的效果是已经验证过的：

- 在同一张会把 `packages-cf.termux.dev` 劫持到 `198.18.0.x` 的 Wi-Fi 上
- 现在 fresh install 已经能成功完成 `apt update`
- 并且开始真实下载 prerequisites，而不是像之前那样直接失败

当前它还没有完全解决的问题不是“错误退出”，而是“在 `PARTIAL_CONNECTIVITY` 的 Wi-Fi 上下载很慢”。

## 5. 当前已落地的关键代码

下面这些文件是目前这一轮路线的关键落点：

- [app/src/main/java/com/termux/app/ClawMobileBootstrapService.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/ClawMobileBootstrapService.java)
  Phase 2 独立 bootstrap 进程。

- [app/src/main/java/com/termux/app/ClawMobileBootstrapState.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/ClawMobileBootstrapState.java)
  Phase 2 状态和日志桥接。

- [app/src/main/java/com/termux/app/ClawMobileHostResolver.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/ClawMobileHostResolver.java)
  app 侧关键域名解析和 `resolved-hosts` 写入。

- [app/src/main/java/com/termux/app/ClawMobileNetworkProxyService.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/ClawMobileNetworkProxyService.java)
  当前安装链路的本地代理。

- [app/src/main/java/com/termux/app/TermuxActivity.java](/Users/ahengljh/Repos/termux-app/app/src/main/java/com/termux/app/TermuxActivity.java)
  launcher overlay、安装编排、Phase 1 启动逻辑。

- [app/src/main/assets/clawmobile_repo/installer/termux/install.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/install.sh)
  Termux Phase 1 入口。

- [app/src/main/assets/clawmobile_repo/installer/termux/common.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/common.sh)
  Termux 侧公共环境和代理接线。

- [app/src/main/assets/clawmobile_repo/installer/termux/bootstrap-ubuntu.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/termux/bootstrap-ubuntu.sh)
  Phase 2 进入点。

- [app/src/main/assets/clawmobile_repo/installer/ubuntu/bootstrap.sh](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/installer/ubuntu/bootstrap.sh)
  Ubuntu bootstrap 本体。

- [app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui/](/Users/ahengljh/Repos/termux-app/app/src/main/assets/clawmobile_repo/openclaw-plugin-mobile-ui/)
  已经去 Droidrun 化的 mobile plugin/runtime 代码。

## 6. 当前真实状态

截至 2026-03-18 的实测状态：

- app branding 和安装入口已经改成 ClawMobile。
- phase 2 独立进程已经落地。
- hosts 预解析已经落地。
- 本地代理已经落地。
- fresh install 时，`apt update` 已经能在坏 DNS 环境下成功。
- prerequisites 安装已经开始真实下载，而不是立即报错。
- 但这一次 cold run 还没有走到 OpenClaw 安装完成，所以**还不能宣布“一键安装已完全可复现”**。

更准确地说，当前状态是：

- **以前的硬失败点已经被打掉**
- **现在剩下的是慢速推进和最终链路验收**

## 7. 当前正在尝试的路子

当前主路线不是继续折腾 Droidrun，也不是继续依赖系统 DNS，而是：

1. 保留 repo 内薄 agent 路线，不回退到 Droidrun。
2. 保留 Ubuntu 作为当前 OpenClaw runtime 容器，先把 one-click 装通。
3. 用独立 bootstrap 进程执行 Phase 2。
4. 用 app 内本地代理兜住 Termux/Ubuntu 的出站访问。
5. 在此基础上继续做完整 cold-run 验证，直到确认能从干净安装走到 OpenClaw 启动。

这条路线的现实目标不是“立刻完美架构”，而是先把“可复现安装”打通。

## 8. 还没解决但已经明确的问题

### 问题 1：on-device ADB 可见性仍不稳定

这依然是后续的真实问题，但它已经不是当前安装链路最前面的 blocker。

长期看，更合理的方向是：

- 用 Android app 内原生 bridge 替代 `adb shell`
- 用 Accessibility + MediaProjection + app 内 localhost RPC 替代对 on-device ADB 的强依赖

这条路线已经讨论清楚，但还没有开始主实现。

### 问题 2：当前 Wi-Fi 网络质量差

当前设备连接的 Wi-Fi 出现过：

- 假 DNS 解析
- `PARTIAL_CONNECTIVITY`
- 大包下载很慢

这不是纯代码问题，但代码必须对这种网络环境更耐受，因为这是实机环境的一部分。

## 9. 接下来的优先级

当前建议的优先级是：

1. 先把当前 one-click cold run 真正跑到 OpenClaw 安装完成。
2. 如果下载仍然长期过慢，就继续增强本地代理的下载稳定性，而不是退回系统 DNS。
3. 等安装链路可复现后，再推进“native Android bridge 替代 on-device ADB”。
4. 最后再考虑是否完全移除 Ubuntu。

## 10. 当前一句话总结

当前我们不是在“修一个脚本”，而是在把一条 Android 上的多层安装链路收敛成可复现系统。

已经证明错误方向的东西包括：

- 继续背 Droidrun 的厚依赖
- 继续相信 Termux 在坏网络里会老实用我们写的 hosts
- 继续让 app 当前 UI 进程直接负责 Ubuntu bootstrap

已经证明值得继续投入的方向包括：

- repo 内薄 mobile agent
- phase 2 独立进程
- app 内网络代理
- 先打通 one-click，再谈彻底去 Ubuntu 化
