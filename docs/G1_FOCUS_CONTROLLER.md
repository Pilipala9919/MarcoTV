# G1-A：原生卡片焦点控制器

日期：2026-11-05。基线：`a9d6cb8b64fd2e9030c82addd22edc40a5e755c0`。功能分支：`codex/g1-native-focus`。

**本 PR 交付纯领域控制器与测试。尚未接入 HomeView、TLS、电视授权或网络输入，不能通过手机/Windows 改变电视桌面焦点。G1 的可见端到端目标未完成。** 这采用用户明确允许的安全切片，不开放未认证的验证接口。无主分支修改、权限变更、APK 安装或发布。

## 代码与调用边界（C）

- [MarcoFocusController.java](../app/src/main/java/com/pilipala/tvhome/MarcoFocusController.java)：原创 Java 8，GPL-3.0-only。只导入 `java.util.EnumSet`，无 Android KeyEvent、网络、包启动或输入注入依赖。
- [MarcoFocusChecks.java](../scripts/MarcoFocusChecks.java)：直接编译并执行上述生产类，不使用另写的模拟控制器。
- [test_marco_focus.py](../scripts/test_marco_focus.py)：Windows/JVM 测试入口；已加入 [test_offline.py](../scripts/test_offline.py) 和公共源码打包清单。
- [test_marco_focus_device.py](../scripts/test_marco_focus_device.py)：同一类及测试编译成 DEX，在已授权设备的独立 `app_process` 中运行。**不是 HomeView 集成，也不是 LAN 客户端。**

当前实际调用链只有 `MarcoFocusChecks → MarcoFocusController → Result/Snapshot`。应用构建会包含该类，但没有生产调用者；没有改动 [HomeView.java](../app/src/main/java/com/pilipala/tvhome/HomeView.java)、[MainActivity.java](../app/src/main/java/com/pilipala/tvhome/MainActivity.java)、PhoneManager/PhoneHttp 或 Manifest。

原有 HomeView `onKeyDown/onKeyUp` 可转交 recents/mover/edge，并在确认时启动应用，因此不复用该入口。未来仅在 UI 线程把控制器的成功状态提交到自有 `row/Row.selected`，再更新滚动、焦点动画和绘制。

## 状态与转换契约（C；T 覆盖见下）

| 操作 | 约束与结果 |
|---|---|
| `replaceLayout(sizes, selections, activeRow)` | 防御性复制；最多 64 行、每行 1024 卡；非空行选中索引必须有效，空行为 -1；activeRow=-1 表示卡片区外；非法输入原子拒绝 |
| 布局事件 | 每次调用都增加 layoutRevision 和 focusRevision，包括尺寸未变的重排/替换；不把“同索引”视为同一卡片 |
| `localFocus(row, selections)` | 每次本地导航事件都调用，即使碰边界或离开后回到原位置；focusRevision 增加，排队的旧快照拒绝 |
| `prepare(snapshot, direction, ttl)` | 仅 UP/DOWN/LEFT/RIGHT；捕获接收时截止时间，最长 1000ms；没有内部队列，也不接受字符串/keycode/坐标 |
| `execute(command)` | 执行时检查线程、所属控制器、单次消费、销毁、epoch、会话、期限、两个 revision 和表面；全部通过后才更新领域状态 |
| 左右 | 单卡一步，边界 NO_CHANGE，revision 不增加；不进入侧栏 |
| 上下 | 跳过空行，使用目标行已记忆的选中列；顶/底边界 NO_CHANGE，不进入顶栏/播放区 |
| 空布局/空活动行 | NO_TARGET；不能自行选择另一行恢复控制 |
| 卡片外焦点 | UNSUPPORTED_SURFACE；不能抢焦点或唤醒 |
| 成功移动 | MOVED，focusRevision 增加 1，layoutRevision 不变；只代表领域状态变化，不证明画面已显示 |
| 重复执行同一对象 | ALREADY_CONSUMED；独立对象但旧 revision 则 STALE_FOCUS；这是进程内防重用，不是网络 commandId 去重方案 |

Snapshot 为不可变快照：row/column 供内部适配器使用，focusId 为会话/布局内的卡片位置标识，不含包名。它不是秘密或授权令牌，也不能跨会话当作设备身份。将来对外响应不得默认暴露应用清单。

### 取消与并发

默认 PAUSED、WINDOW_UNFOCUSED、SCREEN_OFF 均阻止控制。DIALOG、DRAWER、RECENTS、CARD_MOVE、PREVIEW、AMBIENT 各自独立，多个状态不能互相清除。调用方必须传入每类状态的**聚合值**（例如对话框栈非空），不是每个弹窗直接成对开关同一个布尔值。

任一 blocker 从 false 变为 true 都增加焦点 revision 并撤销当前 lease。清除 blocker 不恢复授权；下一安全切片需要再次批准。布局变化与本地遥控只作 revision 冲突处理：仍在有效 lease 内时可重新获取快照，不自动重放旧动作。Activity 销毁永久关闭控制器；新的控制器拒绝旧对象，即使其计数器值相同。

`beginAuthorizedLease()` **只是进程内执行许可的生命周期模型，不实施认证**；epoch 只用来取消旧任务，不能作为网络 token。授权有效期上限 5 分钟，断连/显式关闭/心跳丢失应调用 `disconnect()`。测试直接调用该方法是领域测试前置条件，不能被记录成真实电视批准。

所有入口检查创建线程。未来必须在 Android 主线程创建并使用；构造器本身没有依赖 Android Looper，因此本次只验证线程一致性。未来时钟使用 `SystemClock.elapsedRealtime()`；本次注入单调时钟，时钟回退永久关闭控制器。到期使用 `now >= deadline`，不因快照或输入续期。

## 自动化覆盖与实际结果（T）

Windows 主机：18 场景，2,736 个枚举转换，23,233 个断言通过。小布局枚举覆盖 1–3 行、每行 0–3 卡、所有有效活动位置及其他行首/尾选中组合；**不是所有可能输入空间的形式化证明**。

| 场景组 | 测试中的具体证据 |
|---|---|
| 四方向/边界/空行 | 明确的六步预期位置表、四向单卡边界、小布局不越界/不跳非空行/一格步进/保存每行选择 |
| 焦点与布局冲突 | 本地同位置事件、离开又回到原位置、缩短/清空/恢复/同尺寸布局重排、两个旧快照竞争 |
| 失焦与弹窗 | 遍历所有 Blocker，撤销旧请求、不能重新授权、清除后仍无会话；叠加 DIALOG 和 WINDOW_UNFOCUSED |
| 断连 | disconnect、重连新 epoch、替换 lease；旧请求不能在新会话回放 |
| 超时 | 会话精确截止、快照触发过期、命令排队截止、截止前 1ms、非法 TTL/溢出、时钟回退 |
| 生命周期/隔离 | destroy 幂等、重建实例拒绝旧命令、跨线程所有入口拒绝、失败不移动或消费有效请求 |
| 非法状态 | 空/非法数组、选择越界、超大布局、空参数、外部修改输入数组/旧快照不能篡改控制器 |

复测（先按项目构建说明配置 JDK）：

```powershell
python scripts/test_marco_focus.py
```

完整 Android 构建：标准版、受限版均使用 Java 8、Android API 28 编译库、D8 min-api 23；本次已通过。为满足现有回归套件的产物前提，另构建未修改的 Lite API 14。所有产物使用独立本地验证证书，不是官方发行签名，未安装/分发。`python scripts/test_offline.py` 最终 17 个套件全部通过（24.53 秒），包括新的焦点测试与公开包检查。注意既有受限版测试输出中的“official signing identity”实际上只校验两个 APK 签名相同；本轮独立验证签名不是官方签名。

## 真实电视运行记录（D-runtime，不是 D-UI）

MiTV-ASTP0 / Android 9 / API 28 / 固件 `22.8.30.1385`，既有 ADB 授权。独立 ART 进程执行同一 18 场景、2,736 枚举转换、23,233 断言通过。测试使用合成卡片布局和假时钟，没有读取或改动真实桌面的卡片选择，没有产生局域网控制请求。

复测时在本地设置 `$adb` 为 ADB 可执行文件、`$serial` 为已授权设备标识：

```powershell
python scripts/test_marco_focus_device.py --adb $adb --serial $serial --output private/g1-art.json
```

脚本先运行主机测试，再 D8 打包并推送唯一临时 JAR 到 `/data/local/tmp`，通过 `app_process` 执行，最后删除该确切文件。没有 APK 安装、系统输入、权限更改或服务器监听。报告包含型号/固件、源码及 DEX 哈希、测试输出和清理结果，不包含 serial/IP。构建产物和完整日志留在被忽略的 build/private 中。

## 进入 G1-B 的必要工作（P）

1. 用 Android 平台 TLS/密钥存储做独立 spike：协议至少 TLS 1.2、Android 9 真机兼容、Windows 客户端完整 pin 校验必须先于任何票据发送。审核实现和第三方来源；禁止复用 v2 实验工具的未验证服务端模式。
2. 明确电视开启、短期单次票据、电视确认、5 分钟临时授权和可撤销 UI；旧 PhoneHttp cookie/CSRF 不进入本会话。TLS、授权、重放、限流测试通过后才连接焦点执行器。
3. HomeView 适配器：枚举全部本地焦点/触摸/布局变更入口、聚合弹窗状态、Activity/窗口/屏幕状态；在同一 UI 回调内复查并提交。队列丢弃与租约撤销共同阻止迟到任务。渲染失败不得返回成功 ACK。
4. 独立 Windows 客户端：严格 pin、临时会话、四方向和快照；4KiB 帧、4 条排队、每秒 10 步、commandId/seq 去重及心跳需新增传输层测试。本控制器没有实现这些网络保证。
5. 真机依次完成电视批准→快照→四向可见变化→截图与 revision 对照；分别测试真实失焦、弹窗、本地物理遥控、socket 断开、过期与重新批准。不能用本测试进程或 ADB keyevent 代替。

仅在第 5 项有真实证据后，才能把 G1 最终目标标为通过。当前没有可供端到端验证的安全网络接口。
