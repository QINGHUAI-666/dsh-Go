# Droidspaces 内置引擎后端

> 2026-11-17。方案 C：Android 内置引擎默认改用 Droidspaces，PRoot 只保留为兼容回退。
> 实现分支 `feat/droidspaces-backend`。本文说明边界、启动链、安全前提和尚未验证的部分。

## 0. 结论

- 引擎仍然是同一个 `dsh web`、同一份 rootfs、同一套桥和补丁；换掉的只是**进程所在的世界**。
- `DroidspacesSupport` 优先使用 APK 内置的 Droidspaces 7.2.0 二进制（`jniLibs/libdroidspaces.so`），通过 root 管理器调用；找不到内置文件时才使用设备上已安装的 CLI。容器以 host 网络启动，rootfs 仍是 App 私有目录，安装、升级、安全模式和重置继续由 `RuntimeInstaller` / `EngineUpgrade` 管理。
- 没有 root、内置二进制无法执行或内核检查不通过时，本次启动回退 PRoot，并在状态和诊断里写明原因；不会把设备锁死。首次使用在 `设置 → 诊断 → 开发者选项 → 引擎运行方式` 里安装后端并重启；安装前也可以先用内置 CLI 试跑。
- 这是特权运行方式：容器里的 root 可以触达宿主。默认不打开 `--selinux-permissive`，只在容器读不到 App 私有 rootfs 时由用户显式打开。

## 1. 为什么是 host 网络

`dsh web` 只监听 `127.0.0.1`，App 通过 loopback 登录；手机操控的 MCP relay 还依赖抽象 Unix socket。Droidspaces 默认 NAT 会给容器独立网络命名空间，两者都会断。

所以第一版固定 `--net=host`：

- App 仍连 `127.0.0.1:<port>`，token/cookie 和 Host 校验不变；
- `PhoneGuest` 写的 relay 和抽象 socket 仍在同一网络命名空间；
- 代价是失去网络隔离，容器能碰宿主网络。容器本来就有宿主 root，网络隔离不是这一层的安全边界。

以后若要 NAT，需要同时解决：DSH 监听 `0.0.0.0`、端口转发、`Host` 头/cookie authority、手机 MCP 的宿主地址、DNS 和 App 侧健康检查。这不是一个 `--net=nat` 就能完成的改动。

## 2. 启动链

```text
RuntimeController.startProcess (worker)
  └─ RuntimeBackend == droidspaces?
       ├─ DroidspacesSupport.probe()
       │    ├─ su -c id -u
       │    ├─ 查找 /data/local/Droidspaces/bin/droidspaces（或 PATH）
       │    ├─ droidspaces version
       │    └─ droidspaces check --json
       ├─ GuestSetup / PhoneGuest / SafeMode / overlays（与 PRoot 相同）
       └─ DshProcess.startDroidspaces
            ├─ 写 /opt/dsh-go/droidspaces-{env,command,init}.sh
            ├─ su -c '<droidspaces> --name dsh-go --rootfs <app rootfs>
            │          --init /opt/dsh-go/droidspaces-init.sh --net host
            │          [--selinux-permissive] [--bind ...] --foreground start'
            ├─ 复用 DshProcess 的 stdout/stderr、token 正则和端口健康检查
            └─ stop: su -c '<droidspaces> --name dsh-go stop'
```

### 2.1 guest init

Droidspaces 需要 PID 1。生成的 `droidspaces-init.sh`：

```sh
#!/bin/sh
. /opt/dsh-go/droidspaces-env.sh
exec /bin/sh /opt/dsh-go/droidspaces-command.sh
```

`droidspaces-command.sh` 是现有 manifest 的 guest 命令，已经展开 `{PORT}` 并叠加手机操控、搜索、订阅登录、语音、安全模式等 `--patch`。DSH 退出即 PID 1 退出，容器结束；不需要再维护 `/tmp/dsh-web.pid` 来杀进程。

`droidspaces-env.sh` 由 App 写入，包含 `HOME`、`DSH_HOME`、`PATH`、`TZ`、`DEEPSEEK_API_KEY` 等；文件权限 0600。它和 rootfs 一样在 App 私有目录里，不新增全局凭据文件。

### 2.2 Android root 集成

Droidspaces 在 Android 上不只是那个 466 KB 的 CLI。官方安装文档写明：Magisk / APatch 的 root shell 带 seccomp 过滤，容器运行时需要的系统调用会被拦；它的做法是把二进制和静态 BusyBox 放到 `/data/local/Droidspaces/bin`，由 root 管理器装一个开机模块，启动 `droidspacesd` 守护进程，并在重启时应用 SELinux 规则。KernelSU 是官方推荐路径，其他 root 方案则依赖 daemon 模式。

因此本实现内置了二进制，但**不能保证所有 root 方案都能从 App 的 `su` 直接启动**：

- 能直接执行的设备：bundled `libdroidspaces.so` 走 `su -c`，不需要装 Droidspaces App；
- 被 seccomp / SELinux 拦下的设备：容器启动会失败，需要安装 Droidspaces 的后端（daemon + 开机模块），或切回 PRoot；
- 本实现现在**内置了后端安装器**：`DroidspacesBackend` 把 APK 里的 `droidspaces`、`busybox` 和 boot-module 脚本复制到 `/data/local/Droidspaces/bin` 与 `/data/adb/modules/dsh-go-droidspaces/`，写入 `.daemon_mode=1` 并加上 App 私有 rootfs 的 SELinux 规则；重启后由 root 管理器应用 `sepolicy.rule`、`post-fs-data.sh` 启动 `droidspacesd`。它和官方 Droidspaces App 的后端是同一套脚本，只是模块 id 改成 `dsh-go-droidspaces`，避免覆盖官方模块。

### 2.3 rootfs 和数据

第一版**不搬 rootfs**：

- 仍是 `filesDir/runtime/rootfs`，`RuntimeInstaller` 和 `EngineUpgrade` 完全不动；
- Droidspaces 通过 root 读取这个目录并 pivot_root；
- App 在启动前写 guest 文件、运行中写诊断/技能，权限模型和 PRoot 时相同；
- App 卸载时 rootfs 随 App 数据删除，不会像 Droidspaces 自己的容器那样留在 `/data/local/Droidspaces`。

这条选择换来了最小改动，但引入一个真机待验证点：App 私有目录的 SELinux 标签是 `app_data_file`，容器 PID 1 是否读得到取决于设备和 Droidspaces 版本的 SELinux 处理。读不到时的恢复路径是设置里的「SELinux 宽容模式」，不是重置或重装。长期更稳的做法是把只读系统层放到 `/data/local`，把 `/root`、`/opt/dsh-app` 等可写目录从 App 数据 bind 进去；那是下一步，不在本实现里。

## 3. 生命周期

| 阶段 | 行为 |
|---|---|
| 安装 | 不变：`RuntimeInstaller` 解压 APK 内 rootfs，写 `rootfs.version` |
| 升级 | 不变：停止后 `EngineUpgrade` 替换引擎路径；Droidspaces 下次启动读新树 |
| 启动 | 先 probe；可用则容器启动，不可用则 PRoot；状态记录实际选择 |
| 就绪 | 复用 DSH 的 `dsh web: http://127.0.0.1:...?token=...` 行和端口连接 |
| 停止 | `droidspaces stop`；等待 console 进程退出，超时才 destroy 本地 `su` 进程 |
| 安全模式 | 同一容器命令，`SafeMode.profileArg` 已把 `dsh web` 换成救援 profile |
| 重置 | 不变：停止后删除 App 私有 runtime |
| 崩溃恢复 | 复用 `RuntimeController` 的退出码分类、LKG、strikes；容器退出即进程退出 |

没有做 Droidspaces 的 daemon/开机自启：容器故意和 App 生命周期绑定，避免卸载 App 后仍留下一个带凭据的常驻 root 容器。以后若要常驻，必须同时改通知、会话所有权、卸载清理和隐私说明。

## 4. 安全边界

这是方案 C 最重要的一节。

- **root**：Droidspaces 需要 root；App 通过 `su -c` 调用，root 管理器会显示授权。没有 root 时回退 PRoot。
- **容器 root 可以触达宿主**：Droidspaces 官方明确说它不是沙箱。DSH 会执行模型生成的命令；如果模型被提示注入，影响范围比 App 沙箱里的 PRoot 大。默认建议在容器里使用非 root 用户、只 bind 必要目录、不要 `--privileged`/`--hw-access`；本实现没有自动开这些。
- **SELinux**：默认不开 `--selinux-permissive`。开启后宿主 SELinux 在容器运行期间变为宽容，风险更高；设置页写明，且只在需要时开。
- **rootfs 权限**：rootfs 在 App 私有目录，卸载即删；不会复制到 `/data/local`。这是第一版相对 Droidspaces 原生容器的隐私优势。
- **凭据**：`DEEPSEEK_API_KEY` 只写进 rootfs 内的 0600 文件；日志仍走 `LogRedactor`；token 不写状态。
- **网络**：host 网络，没有容器网络隔离；不要把它描述成比 PRoot 更安全。它的收益是性能和 namespace 隔离（PID/mount/UTS/IPC/cgroup），不是防逃逸。

## 5. 兼容和回退

- 旧 APK / 旧 rootfs 没有 Droidspaces 相关内容也能启动：Dart 侧字段缺省 `proot`，原生侧没有 CLI 就回退。
- 切回 PRoot：设置 → 诊断 → 开发者选项 → 引擎运行方式 → PRoot 兼容；或清理 `runtimeBackend` 偏好。
- 直接启动 / tawcroot 仍在，但只在回退 PRoot 时生效；Droidspaces 启动不会用它们。
- Droidspaces CLI 版本、容器配置格式、内核检查输出都可能变；`probe` 只依赖 `version`、`check --json` 和 `start/stop`，不直接读写容器配置。

## 6. 内置二进制与许可

Droidspaces 7.2.0 的 aarch64 / x86_64 二进制、BusyBox 和 boot-module 脚本来自上游 APK / release `droidspaces-v7.2.0-cdadb9eb.tar.gz`，以 `libdroidspaces.so` / `libbusybox.so` 打包进 `app/android/app/src/main/jniLibs/<abi>/`，由 Android 的 `useLegacyPackaging` 解出到 `nativeLibraryDir`；boot-module 脚本在 `app/android/app/src/main/assets/droidspaces/`。SHA-256、版权、GPL-3.0 全文与对应源码地址登记在 `THIRD_PARTY_NOTICES.md`。Droidspaces 本身保持 GPL-3.0；dsh-go 的 APK 源码按 AGPL-3.0 提供。

## 7. 未验证 / 已知边界

- **未在真机验证**：本机是 aarch64，没有 Flutter/Android SDK，无法编译 APK、跑 Flutter/Kotlin 测试或真机启动。
- **后端安装后需要重启**：首次安装只写好文件和模块，SELinux 规则和 daemon 要等重启；重启前可尝试 bundled CLI，失败则 PRoot。
- **App 私有 rootfs 的 SELinux 规则**：模块里加入了 `app_data_file` 的 allow，但没有真机验证过；如果策略被 root 管理器拒绝，容器会起不来，需要看 `Logs/boot-module.log` 或切回 PRoot/宽容模式。
- **官方 Droidspaces App 共存**：模块 id 不同，但两个 daemon/二进制可能抢 `/data/local/Droidspaces`；同一时间只应有一套后端。
- App 私有 rootfs 在 SELinux enforcing 下是否可读：待真机；失败用宽容模式兜底。
- root 管理器的 `su -c` 行为（KernelSU / Magisk / APatch）和授权弹窗：待真机。
- Droidspaces 对 `--init` 脚本、`--net host`、App 私有目录、Android 字体 `/system` bind 的具体行为：待真机。
- `droidspaces stop` 的退出时间、console 进程回收、OEM 进程管理：待真机。
- DSH 内部再用 `dsh`/`bash` 子进程、终端、Node 原生模块在容器里的行为：待真机。
- 手机操控的抽象 socket 在 host 网络下是否与 App 侧一致：待真机。
- 未做容器网络隔离、开机自启、daemon、非 root 用户、`/data/local` rootfs、增量升级容器镜像。

## 8. 验证

已做：

```text
python3 app/tool/l10n_merge.py --check
python3 app/tool/guide_sync.py --check
```

未做（本机 aarch64，无 Flutter/Android SDK）：

```text
cd app && flutter analyze && flutter test
cd app/android && gradle :app:testArm64DebugUnitTest
tooling/build-apk.sh --build-name 1.1.0
```

新增的纯逻辑测试：

- `app/android/app/src/test/kotlin/.../DroidspacesBackendTest.kt`：后端默认值、bind 归一化、启动命令、带空格路径引用。
- `app/test/runtime/droidspaces_status_test.dart`：`RuntimeStatus` / `DroidspacesStatus` 缺省与可用性解析。

真机验收至少覆盖：

1. KernelSU / Magisk 授权后 `droidspaces check --json` 通过；
2. 首启安装 rootfs → 容器启动 → token → 对话；
3. 工具命令、审批、文件写、终端、手机操控；
4. 停止/重启/安全模式/引擎升级；
5. 没有 root、没有 Droidspaces、内核不满足三种回退；
6. 卸载 App 后不留下容器和凭据。
