# G1-B2 · Windows 控制 Marco TV 卡片焦点

**状态：2026-11-07 真实电视授权 RIGHT 视觉验收通过；异常场景验收尚未完成，G1-B2 保持未完成。** 基线为 MarcoTV PR #2 的领域控制器、PR #3 的最终证据提交 `7d435c3` 及 MarcoRemote 的 G1-B1 报告。B1 的有条件通过允许开始此次集成。旧验证包的假计数与人工授权记录仅属于 B1。

## 实现与审查

- 电视用户在桌面设置选“安全卡片遥控”，再选“开启配对”。应用在自身 UID 下生成独立 EC P-256 AndroidKeyStore 身份，alias 为 `marco-home-focus-tls-v1`。验证时另用并排安装的 UID。窗口只绑定一个本机私有 IPv4 地址和临时端口。电视在带防截屏标记的本地弹窗展示含证书、完整指纹、设备身份、地址与一次性票据的 QR。运行时不写出配对文件。旧 PhoneHttp/PhoneManager 不持有这些凭据。[FocusIdentity](../app/src/main/java/com/pilipala/tvhome/FocusIdentity.java) · [FocusRemote](../app/src/main/java/com/pilipala/tvhome/FocusRemote.java)
- 扫码文本通过用户掌握的带外渠道进入 Windows。CLI 使用 Python 标准库 TLS 1.2、唯一带外证书、CERT_REQUIRED、自签名和时间检查、完整 leaf pin 与 CN 身份比对，校验成功才发送 PAIR。正确票据单次消费，等待本机批准；对话框默认焦点为拒绝。每个连接绑定独立会话，断开后重新配对。[TLS 客户端](../remote/tls_link.py) · [Windows CLI](../remote/marco_remote.py)
- 临时窗口 120 秒，待批准至多 30 秒，获准会话 5 分钟，空闲 15 秒。每个授权帧消耗一个严格递增的 seq，每秒上限 10 帧。STATE 仅返回 epoch、布局版本、焦点版本、行列和一次性 challenge。STEP 仅接受四个方向。服务端保存当时的原始 Snapshot，命令截止取 STATE 接收后 1 秒和会话截止的较早值。传输层把收帧单调时间带到 UI；排队超过 1 秒会失败。[FocusSession](../app/src/main/java/com/pilipala/tvhome/FocusSession.java) · [FocusTransport](../app/src/main/java/com/pilipala/tvhome/FocusTransport.java)
- 每次本机开启创建独立策略和 transport。异步密钥创建使用 Activity 代次，后续回调比较当前 transport 实例；UI 持策略锁核对连接、会话、序号、期限、challenge 与原始 Snapshot，再执行受限移动。同步撤权使用同一把锁。先获锁并完成的移动排在撤销之前，撤销后的排队任务失去授权。读线程可在 UI 排队时观察 EOF；写回复与 close_notify 都在后台。无 FIN 的静默断网依赖空闲截止。[授权与撤销](../app/src/main/java/com/pilipala/tvhome/FocusRemote.java) · [网络传输](../app/src/main/java/com/pilipala/tvhome/FocusTransport.java)
- UI 提交检查 Activity 前台、屏幕交互、HomeView 窗口/视图焦点、弹窗、边栏、最近应用、移动编辑、预览、屏保和目标卡片。控制器核对 epoch、layoutRevision、focusRevision 和绝对截止。MOVED 在同一 UI 回调更新真实 `row/Row.selected`，调用 ensureVisible、animateFocus、describeFocus 和 invalidate。Windows 的回执表示选中状态已提交；画面证据由单独的实测取得。[HomeView](../app/src/main/java/com/pilipala/tvhome/HomeView.java) · [MarcoFocusController](../app/src/main/java/com/pilipala/tvhome/MarcoFocusController.java)
- 现有实体按键/触摸路径保留本地导航语义。按键处理结束同步所有行选中位置，即使按在边界也使旧快照失效。按下确定键或开始触摸时撤销，防止按住再松开期间焦点被网络请求移走。卡片加载、排序（含同尺寸重排）、尺寸变化更新布局版本。设置、暂停、Activity 销毁/新 Intent 和前台窗口失焦会取消。按方向键移到顶栏或播放区也会结束当前授权。[AppCatalog](../app/src/main/java/com/pilipala/tvhome/AppCatalog.java) · [PhoneManager 重排入口](../app/src/main/java/com/pilipala/tvhome/PhoneManager.java) · [MainActivity](../app/src/main/java/com/pilipala/tvhome/MainActivity.java)
- 正式源码只包含受限输入适配。独立验证包、诊断 Intent、ADB bootstrap 文件、自动批准测试夹具均在应用构建边界之外。并排构建仅声明 MainActivity 与 INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE，使用独立本地签名且不可调试；标准应用 Manifest 沿用原权限。两种自动工作流都只在手动触发时上传构建产物。[构建入口](../scripts/build.py) · [构建工作流](../.github/workflows/build.yml)

## Windows 与电视复现

1. 按项目构建说明准备 JDK/Android 工具。运行 `python scripts/build.py --focus-lab`。只安装 `build/focus-lab/apk/MarcoTV-0.15.0.apk` 对应的 `com.pilipala.tvhome.focuslab`。它以单独 UID 运行同一套 MainActivity、HomeView、控制器和 TLS 传输。不要用正式包覆盖安装。
2. 唤醒电视，打开“Marco TV · 卡片遥控验证”，如出现引导可选“跳过引导”。选择有至少两张卡片的应用行，通过菜单键进入桌面设置。进入“安全卡片遥控”，明确选“开启配对”。开启配对会返回默认卡片行。
3. Windows 运行下面的 CLI，使用可信本地扫码器读取电视 QR，将完整文本粘贴到隐藏输入提示。票据不进入命令行参数；不要发送到聊天、使用在线解码器或保存进共享剪贴板历史。电视端核对电脑地址后亲自批准。每次试验开启新窗口。二维码保留 120 秒；超时后重新开启即可。

```powershell
python remote/marco_remote.py
# 或一次配对后按顺序执行，并主动关闭
python remote/marco_remote.py STATE RIGHT STATE CLOSE
```

交互命令为 STATE、UP、DOWN、LEFT、RIGHT、CLOSE。返回位置字段依次为 epoch、layoutRevision、focusRevision、零起始行、零起始列。用户思考期间由 Windows 保活。客户端不会重发失败移动。打开电视桌面设置即可主动撤销；再次使用要重新扫码与批准。

**同一次 RIGHT 的取证**：另一次本机配对前运行下列命令。脚本复用 CLI 的配对、证书校验和请求代码。ADB 只在获准后读取前台窗口并截屏，不读取信任材料、不输入按键。先保存 before.png，再读取 STATE，核对与第一张画面的状态一致，立刻使用该 challenge 发送 RIGHT。等待动画结束后保存 after.png 与最终 STATE。要求行、epoch、布局版本保持一致，焦点版本与列都增加 1。两张图与 observations.json 保存在 `private/focus-witness/<时间>/`。人工检查两图中相邻卡片的焦点变化，之后才登记 D-UI 通过。哈希或图片差异本身不足以判定成功。

```powershell
python remote/tests/device_witness.py --adb $adb --serial $serial
```

[故障采集工具](../remote/tests/device_faults.py) 的每个模式都需要新扫码和电视批准。例：`python remote/tests/device_faults.py replay --adb $adb --serial $serial`。local 模式在 NOW 后 700ms 内用实体遥控按一次方向键，保留旧 challenge 的响应。replay 重发已使用的 seq。stale 和 idle 分别等待挑战与空闲截止。revoke 用遥控器打开设置，sleep 用物理电源键。recreate 仅对并排测试包清空任务并启动新 Activity，记录进程是否保持；需核对系统 Activity 销毁/创建事件，不涵盖保存状态恢复的所有生命周期。network 需在电视 UI 外中断电视网络，例如拔出网线；恢复网络后再取电视日志。Windows 超时只表示客户端观察到失联，不能单独证明电视已撤权。

撤销/休眠/重建/断网前后分别记录 Windows 时间、电视 `MarcoFocus` 日志、对应包的 Activity 和 crash buffer 时间窗。仅收集相关片段，不清空全设备日志。重建或重新联网后应需重新本地配对；再次完成有效 RIGHT 前不得沿用旧凭据。所有模式的结果文件默认写入本地忽略目录。

## 真实 RIGHT 结果（2026-11-07）

Windows 使用 `device_witness.py` 经用户扫描新 QR、粘贴本机隐藏输入并在电视批准后，发送一次 RIGHT。电视日志依次出现 TV_APPROVED（23:28:38.082）、STEP MOVED（23:28:43.599）、LOCAL_SESSION_ENDED（23:28:48.468）。用户报告“成功了”；人工复核原始截图显示选中卡片从 Marco Movie 移至右侧 Moonlight。epoch/layoutRevision 保持 1/1，focusRevision 从 4 到 5，行列从 0/0 到 0/1，最终 STATE 与 MOVED 回执一致。

[结构化实测记录](evidence/g1-b2-right-20261107/observations.json) · [移动前](evidence/g1-b2-right-20261107/before.png) · [移动后](evidence/g1-b2-right-20261107/after.png)。截图无 QR 或配对凭据。原始私有 observations 保留不改；公开记录补充人工复核和证据范围。安装版本沿用前一日记录，不将旧安装记录中的 pending 状态改写成当日通过。

TLS 证据来自真实会话通过固定证书校验代码并取得授权后的 STATE/STEP，不额外声称留有握手抓包或控制台逐字记录。脚本随后调用 CLOSE，电视记录会话结束；未单独保存 CLOSE 回执。此次 crash buffer 检查未返回条目，但不能代替本机主动撤销测试。首次尝试用户只扫码、未向 Windows 提交文本，因此没有批准弹窗或移动；补全既定配对流程后成功，没有绕过校验或修改代码。

[真实重放拒绝记录](evidence/g1-b2-replay-20261107.json)：23:31:18 电视明确批准的新会话内重复已用 seq，客户端收到 REPLAY 和 EOF，电视同步记录 STEP REPLAY、LOCAL_SESSION_ENDED。此轮没有保存最终位置或截图，只登记拒绝决策与断开通过。

[真实主动撤销记录](evidence/g1-b2-revoke-20261107.json)：23:33:43 获批后，用户用实体遥控打开桌面设置；23:33:47 电视记录会话结束，Windows 收到 EOF，进程仍在、应用仍前台，用户确认正常。检查时 crash buffer 无条目、该进程日志没有 NetworkOnMainThreadException 或 FATAL EXCEPTION。只登记这次路径通过，不声称所有取消竞态均已实测。

[真实休眠/唤醒记录](evidence/g1-b2-sleep-20261107.json)：23:35:31.064 会话结束，31.096 系统停止验证进程，31.112 请求休眠；23:35:45 唤醒，用户确认正常但回到小米桌面。Windows 收到 EOF，检查时 crash buffer 无条目。重新显式打开验证版后进程 PID 变为 20227，采样 TCP 监听数为 0。休眠断连通过；不把进程存活、自动回到 Marco 或旧凭据重新连接测试列为通过。采集脚本因 pidof 未找到已停止进程，缺少 alive/asleep 字段，以独立系统事件补证并保留原始文件。

[活跃会话页面替换记录与画面](evidence/g1-b2-recreate-20261107/observations.json)：23:37:56 批准后，以 NEW_TASK|CLEAR_TASK 显式重开验证版，23:37:57 旧会话结束、Windows 收到 EOF；PID 20227 保持，系统确认旧 Activity 销毁和新 Activity 创建，截图显示 HomeView 正常。用户看到一次闪烁，与本次主动页面替换时点一致。此项不覆盖配置变化恢复或全部重建竞态，也未在重建队列中注入旧 STEP。

[首次物理冲突尝试](evidence/g1-b2-local-missed-20261107.json) 返回 MOVED，用户明确反馈错过 NOW 提示，故记录为未验证，不能据此认定冲突保护通过或失败。测试工具随后在获取 STATE 前增加 5 秒倒计时，STATE 后的 700ms 本地操作窗口与服务端 1 秒截止不变；不修改 Android 代码或放宽安全期限。

[第二次物理冲突测试](evidence/g1-b2-local-conflict-20261107.json)：采用 STATE 前 5 秒倒计时，用户确认完成 NOW 后实体右方向键操作，23:41:20.862 双端记录 STEP STALE_FOCUS，旧快照命令被拒绝。此项是实际焦点版本冲突，而非到期或通用撤销；未保存最终位置截图，不扩展为全部实体按键竞态已通过。

## 证据与验收矩阵（更新至 2026-11-07）

C 表示源码审查，T 表示主机自动化，B 表示构建，D 表示本次真实电视观测。安装记录及 APK SHA-256 见[设备证据](evidence/g1-b2-install-20261106.json)。

| 场景 | 已取得的结果 | 待取得的设备证据 |
|---|---|---|
| 自动化与原有回归 | T 通过。16 个 TLS/CLI 测试（11 项证书/帧 + 5 项端到端主机连接），另有 Java 策略/控制器组合断言。原有 17 套离线测试全通过 | 主机 UI 夹具使用独立执行线程与控制器 |
| 独立 Android 构建 | B 通过。API28 编译、D8 min23、APK 签名/对齐通过。DEX 含真实 HomeView、控制器、TLS 与会话类 | D 安装成功，本地与安装 APK 哈希一致 |
| 默认关闭与身份隔离 | D：小米 MiTV-ASTP0/API28 上测试 UID 10040，正式 UID 10059，旧 probe UID 10039。MainActivity 已启动，采样时测试 UID 的 TCP 监听数为 0 | D：本次新 UID 下已批准连接并完成真实 STATE/STEP；无单独握手抓包 |
| 本机拒绝/批准与错误证书 | T：合成证书的错误信任根、指纹、身份、过期、未来日期、坏签名均验证失败；主机拒绝不授予会话 | D：B2 本机批准和真实认证连接成功；拒绝与错误证书仍需单独实测 |
| RIGHT/其他方向、状态、关闭 | C/T：真实协议与 reducer 上四向移动、边界、状态和主动 CLOSE 通过 | **D-UI：RIGHT 通过，前后画面与双端状态一致。** 其余三方向、边界和 CLOSE 回执独立留证待补 |
| 旧命令重放与陈旧快照 | T：重复 seq 撤权，challenge 单次使用，原始 revision 失效、延迟排队及截止均验证 | D：2026-11-07 新授权后重复 seq，Windows 收到 REPLAY 和 EOF；电视记录 STEP REPLAY 后会话结束，进程仍在。未保存此轮最终位置/画面；陈旧快照另测 |
| 本地物理遥控冲突 | T：同位置本地事件和等尺寸布局替换均使旧命令失效；C：HomeView 接入实体事件 | D：第二次 NOW 后实体右方向键操作，Windows 和电视均返回 STALE_FOCUS，通过此次原快照冲突拒绝；首次错过时机的尝试保留未验证。无此轮最终截图 |
| 休眠/唤醒、弹窗、Activity 重建 | C/T：阻塞、销毁、代次和丢弃排队命令已覆盖；D 安装采样时电视处于 Asleep | D：打开设置已断开；物理休眠前会话结束，系统随后停止进程，唤醒回小米桌面。重新打开验证版采样无监听。D：活跃会话任务替换已断开，同进程新页面正常；配置变化恢复、排队旧 STEP 与旧凭据重连仍待测 |
| 网络中断 | T：真实主机 TCP 断开时 UI 尚有延迟任务，任务实际返回 UNAUTHORIZED，列保持 0。T 空闲及绝对期限通过 | D：客户端断链及静默丢包/物理 Wi-Fi 中断分别记录；EOF 不能代替物理网络故障 |
| Android 9 撤销时关闭 socket | C：UI 同步撤权，单独线程释放 TLS 传输；B1 曾有同类问题并修复 | D：2026-11-07 获批后实体遥控打开设置，Windows 收到 EOF，电视 LOCAL_SESSION_ENDED，进程/前台仍在，用户确认正常；crash buffer 无条目，本进程日志无 NetworkOnMainThreadException/FATAL EXCEPTION。仅本次路径通过 |

另一次真机采样在电视仍为 Asleep、测试 UID 无监听、没有已授权会话时进行了实验包任务替换。系统事件记录了新 Activity 创建与旧 Activity 销毁，进程保持，测试 UID 的监听数仍为零。此项只覆盖默认关闭状态下的 Activity 替换，活跃授权的取消仍按上表待测。

自动化入口（Windows，测试环境使用既有审核的 cryptography 50.0.2，运行 CLI 只需 Python 标准库）：

```powershell
python remote/tests/check.py
python scripts/test_offline.py
python scripts/build.py --focus-lab
```

[逐项自动化结果与耗时](evidence/g1-b2-host-tests-20261106.json) · [协议/断连集成测试](../remote/tests/check.py) · [组合状态机测试](../remote/tests/FocusSessionChecks.java) · [主机 TLS 测试夹具](../remote/tests/FocusTransportFixture.java)

## 复核时发现的问题与剩余风险

最初的适配在排序前同步布局，非活动行可能保留旧的记忆列。已将同步放到排序、目录替换及原有管理端重排之后。客户端缓慢或 UI 排队时，传输读线程仍需观察断开；已将读取和 UI 提交分开，用主机实际断链测试覆盖撤权先于排队提交的次序。另已保护实体确定键按下/松开间隔，避免网络移动改变用户即将打开的卡片。

真实 TV 的已批准 TLS 会话和可见 RIGHT 已有证据。已批准会话中的设置触发主动撤销和重复序号拒绝已有独立实测。休眠断连已有实测，但该固件会停止验证进程并在唤醒后回小米桌面。活跃任务替换已观察到断连和新页面正常；实体右方向键与旧快照冲突已实测拒绝。其他重建方式、其他按键竞态、网络中断及其他未记录场景仍分别待测；不能由一次 RIGHT 成功推定通过。Android 9 系统 TLS 补丁状态、外部扫码器如何处理秘密和多网卡地址选择仍是部署限制。默认选取第一个可用的私有 IPv4；不可达时应关闭并重新配对。单窗口只接受一个连接，同网其他设备占用连接可能导致用户重新开启窗口。

所有改动在功能分支上。HomeView 安全入口没有通用 KeyEvent、Intent、Shell 或跨应用操作；本轮提交供审查，发布与合并由项目维护者决定。
