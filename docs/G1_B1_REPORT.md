# G1-B1：Android 9 TLS 与配对验证报告

更新：2026-11-06，Asia/Shanghai。分支 `codex/g1-b1-secure-session`，基于 G1-A `8ab4ec1dc0c82d1abb1c0b1662128359a19955d9`。实现 [PR #3](https://github.com/Pilipala9919/MarcoTV/pull/3)，最终受测代码 `943580c894ca1083d276ad1ddbf72ac37c5c8cbe`。没有修改 main 或稳定版安装；验证 APK 为独立包、独立签名，未发布。

## 当前判定

**本机主动配对、明确拒绝/批准、Windows 严格 TLS 校验、假输入、重放拒绝、过期 challenge、断线、主动撤销、空闲关闭和 5 分钟绝对期限均取得真实电视证据。G1-B1 技术验证结果已交付，等待用户审查；不自动进入 G1-B2。**

11 月 5 日的“仅完成 TLS、人工会话待测”结论已由本次补测更新。测试中发现 Android 9 撤销时崩溃并修复；保留失败记录，不把所有尝试都归为成功。不同场景在不同提交上运行，见下表；最终提交只复测了 5 分钟会话，不声称全部人工场景均在最终 APK 上重跑。

命令仅接假计数器。真实桌面卡片焦点未接入、未验证；APK 不含 MarcoFocusController、HomeView 或主机自动批准夹具。

## 三层结果及证据矩阵

C=源码依据；T=自动化；B=Android 构建；D=真实电视/Windows LAN 与用户实体遥控操作。各层互不替代。

| 证据层 | 结果 | 范围与边界 |
|---|---|---|
| 单元/策略 T | **通过** | 24 场景/126 断言；BackgroundClose 回归验证同步撤权、非调用线程 I/O、32 个并发关闭请求、幂等清理 |
| 主机 TLS T | **通过** | 11 个 Windows 证书/帧测试；8 个实际 Java transport + policy 测试。合成证书、主机自动批准，不能替代 D |
| 原有离线 T | **通过** | 修复后重跑 17 套件，29.77 秒，包含 G1-A；本地日志 `build/security-spike/manual-fix-offline.txt` |
| Android 构建 B | **通过** | 最终独立 probe：API28 编译、D8 min23、签名/安装、已安装 APK 哈希一致。自动 CI 还构建标准/受限/Lite；不等于桌面集成测试 |
| 真实电视 D-TLS | **通过，指定设备** | 6 项早期诊断：两次正确握手、错误 pin、错误信任根、错误身份、进程重启身份保持 |
| 真实电视 D-session | **下表所列成功证据已取得** | 真实本机授权及 LAN 假输入；保留旧版失败与屏保中断，仍需用户阶段审查 |
| 真实桌面 D-UI | **未执行** | 没有 HomeView/MarcoFocusController 桥接，不证明可见卡片移动 |

调用链：[DeviceIdentity](../security-spike/src/com/pilipala/remoteprobe/DeviceIdentity.java) → 平台 KeyStore/JSSE；[client.connect](../security-spike/tools/client.py) 全部验证成功 → PAIR；[ProbeActivity](../security-spike/src/com/pilipala/remoteprobe/ProbeActivity.java) 本机批准 → [SessionPolicy](../security-spike/src/com/pilipala/remoteprobe/SessionPolicy.java) 临时授权 → [ProbeServer](../security-spike/src/com/pilipala/remoteprobe/ProbeServer.java) → policy 假计数。撤销通过 [BackgroundClose](../security-spike/src/com/pilipala/remoteprobe/BackgroundClose.java) 立即撤权，再后台关闭 TLS。

自动化入口：[策略/关闭测试](../security-spike/tools/test_policy.py)、[Windows 客户端测试](../security-spike/tests/test_client.py)、[传输测试](../security-spike/tests/test_transport.py)。策略/关闭/transport 与离线套件在 `b704b2a` 修复后重跑；`943580c` 只增加已批准会话期间的窗口亮屏标记，重新构建并运行 case11。最终代码的 [PR CI](https://github.com/Pilipala9919/MarcoTV/actions/runs/42063767912) 与 [push CI](https://github.com/Pilipala9919/MarcoTV/actions/runs/42063758656) 均成功，上传产物数均为 0。CI 包含策略/关闭回归和 Android 构建，不包含人工配对，也未运行那 11/8 个主机 TLS 测试。

## 真实设备与证据来源

- MiTV-ASTP0；Android 9 / API28；固件 `22.8.30.1385`。Windows Python 3.13.13 / OpenSSL 3.0.19。
- 早期 TLS 诊断：[g1-b1-tls-20261105.json](evidence/g1-b1-tls-20261105.json)，保留当时源码/APK 哈希及失败修正摘要。
- 本次人工会话：[g1-b1-manual-20261106.json](evidence/g1-b1-manual-20261106.json)，含 11 次尝试的分类、Windows/电视事件、原始文件哈希、各次代码版本、最终源码/APK 哈希与复核结果。
- 实际 TLS 经 LAN 直接建立，无 `adb forward/reverse`。已有授权 ADB 仅用于安装/打开独立验证页、带外读取信任包和采集日志；主动开启、批准、拒绝、按钮撤销均由用户实体遥控完成，未用 ADB 输入替代。
- Windows 记录来自调用公开 `client.py` 的 connect/send/receive 函数的本地采集包装；凭据仅在内存/忽略目录使用。可重复命令与双端日志步骤见 [README](../security-spike/README.md)。公开证据无票据、session、challenge、证书 bootstrap 或 LAN 地址。
- TV 事件时间是电视 wall clock，Windows 有 UTC 与部分单调时钟。二者未进行时钟同步，不据此声称跨机端到端延迟。下述 301.068 秒只取 Windows 同一单调时钟。

## 人工场景结果

表中编号对应 JSON 的 `cases[].id`。`fa268c`、`b704b2a`、`943580c` 是该仓库提交前缀。

| 场景/证据编号 | 实际观察 | 判定与版本 |
|---|---|---|
| 01b-deny：拒绝 | certificate_verified → PENDING；用户选拒绝，TV LOCAL_DENY，客户端 DENIED_OR_EXPIRED，无 GRANTED | D 通过；fa268c。捕获开始时窗口已经开启，本机动作由用户确认 |
| 02 / 07：批准后撤销 | fakeCount=1 后用户按撤销，连接结束但验证页崩溃；07 有 LOCAL_REVOKE_REQUEST，无完成事件 | **失败**；02=fa268c，07=临时仅加日志版；不能算按钮撤销通过 |
| 03-replay | 本机批准后 SNAPSHOT=0；重复 seq=1 返回 REPLAY，listener 关闭，无 FAKE_APPLIED | D 通过；fa268c |
| 04-stale | 取得 challenge 后等待约 1.2 秒，返回 STALE_CHALLENGE，无 FAKE_APPLIED；随后 CLOSE | D 通过；fa268c；证明 challenge 过期，不是票据或证书过期 |
| 05-idle | 15 秒前连接已中断，并出现旧版关闭崩溃/再次开启 | **无效的空闲期限证据**；fa268c，以下 09 替代 |
| 06-disconnect | Windows 主动关闭，TV EOFException → LISTENER_CLOSED | D 通过；fa268c；不是 Wi-Fi 物理断网测试 |
| 08-fixed-local-revoke | 本机批准、fakeCount=1；用户撤销；LOCAL_REVOKE_REQUEST → LOCAL_REVOKE → LISTENER_CLOSED，Windows EOF；用户确认验证页仍在 | **修复复测通过**；b704b2a。窗口开启前的旧 LIFECYCLE_PAUSE 不属于该获批会话 |
| 09-fixed-idle | 本机批准后 SNAPSHOT=0，不发保活；TV 在最后 SNAPSHOT 后约 15.017 秒记录 SocketTimeoutException 并关闭；无新假输入 | D 通过；b704b2a |
| 10-fixed-absolute | 持续保活约 60 秒后 TV LIFECYCLE_PAUSE → 关闭；前台为小米屏保 | **通过暂停取消，未证明 5 分钟到期**；b704b2a |
| 11-fixed-absolute-awake | 用户批准并保持页面；fakeCount=1，59 次 PONG；Windows 在批准后 301.068 秒观察 EOF，TV LISTENER_CLOSED | **绝对期限复测通过**；943580c。期间无 LOCAL_REVOKE/LIFECYCLE_PAUSE，保活未延长会话 |

最后一轮 Windows 获准时间为 **2026-11-06 00:21:09.874 北京时间**（UTC 2026-11-05 16:21:09.874），观察断开为 **00:26:10.942**。TV 自身事件中 SESSION_GRANTED 到 LISTENER_CLOSED 约 300.040 秒，仅用作同机日志佐证。到期后再次读取前台为 `com.mitv.screensaver/.RenderActivity`，已恢复屏保。保留的 crash buffer 中，修复后的 08–11 时段无新增 FATAL EXCEPTION；不是对所有未来运行的保证。

| APK 对应范围 | SHA-256 |
|---|---|
| fa268c；早期诊断、01b–06 | `4fc286775712be27dbd2368bd60c1d0087ce0a16f2328cf366ec9f9b429d300f` |
| 07 临时日志版，失败，不作验收版本 | `c9bd8fe4ef930ccea5fc975f86c9622c7ef4e0f61b82944be248f6b595726d34` |
| b704b2a；08–10 | `50e5538256491c6f6473bad651e70ea121fa4fa4a2985958046bd464df834e72` |
| 943580c；11，最终本地与安装包再次比对一致 | `d85a1fe9cede1d89a114647e17599194977b3baf50a779bc0781d1569e26a92f` |

## 发现的问题与修复

1. **AndroidKeyStore 用途兼容性（早期诊断）**：只允许 SHA 系列 digest 时，本机 Conscrypt 已摘要 TLS 签名报 `Incompatible digest`。为独立试验 alias 增加 DIGEST_NONE 后实际握手成功，未导出私钥或关闭证书验证。私钥 getEncoded()==null、KeyInfo hardwareBacked=true 是本机 API 观测，不是独立硬件 attestation。进程重启保持 pin，不外推卸载或电视重启。
2. **主线程 TLS 关闭崩溃（本次人工测试）**：旧实现从按钮直接 close()，Android 9 栈为 `onClick → ProbeActivity.stop → ProbeServer.close → ConscryptFileDescriptorSocket.close → NetworkOnMainThreadException`。初次只有 EOF 时不足以判定成功；用户报告自动退出后，crash buffer 确认根因。`b704b2a701f3ab02676e2cd001dcdec1304ae030` 改为同步撤权、唯一后台 worker 关闭 socket，新增并发回归测试，08 实机复测确认页面保留。
3. **屏保干扰绝对期限测量**：10 在一分钟进入屏保，正确触发 onPause 撤销，不能算五分钟期限通过。`943580c` 仅在本机明确批准成功后设置 FLAG_KEEP_SCREEN_ON，stop/暂停/撤销/到期清除；未改系统屏保设置、未新增权限。11 在该条件下完成真实五分钟测量，到期后屏保恢复。该标记是验证包便利措施，不预先决定 HomeView 行为。
4. **开发采集旧文件**：早期诊断重启时曾读取旧端口而连接拒绝，已改为等待新诊断文件；初始人工 watcher 无操作超时的 01 不列成功。原始失败留本地，公开摘要保留与安全结论相关的失败。

## 仍未验证与下一阶段门槛

| 能力/边界 | 当前证据 | 不得扩大的结论 |
|---|---|---|
| 票据自然 120 秒到期、等待批准 30 秒到期、5 次错误票据、旧票据再用、跨连接 session 使用、限流 | C/T；见策略/传输测试 | 本次未逐项做真实电视负例，不写成 D 通过 |
| 证书过期/尚未生效/坏自签名 | T 合成证书；真实错误 pin/根/身份为 D-TLS | 没有修改电视时钟或安装坏证书进行真实到期测试 |
| 物理 Wi-Fi 断开、路由中断、电视整机重启/卸载、后台长时压力 | 未执行 | 主动 socket EOF、进程重启与 onPause 不能代替它们 |
| 消费者扫码、持久客户端身份、生产凭据轮换、系统 TLS 补丁/SBOM 全面审计 | 未实现/未完整审计 | debug/已授权 ADB 带外引导不是生产配对发布方案 |
| 本地遥控冲突、弹窗、布局、失焦下的真实 HomeView 提交 | C 审查与 G1-A T；无 D-UI | 不能由假计数安全测试推出真实焦点可用 |

[安全协议](G1_B1_SECURITY_PROTOCOL.md) 已列出接网风险：保存原始服务端 Snapshot/epoch/revision；从接收/快照时起算期限；在 UI 提交前同时检查原子撤权 generation、当前状态和最早截止；渲染失败不得回复可见成功。当前控制器保持不变。

用户通过 G1-B1 审查后，G1-B2 最小任务为：受限 HomeView 适配器、会话/原始快照与 revision 绑定、UI 提交前最终校验、Windows 四方向实测，以及弹窗/本地遥控/布局变化/失焦/断线/超时取消。仍禁止通用 KeyEvent 转发、跨应用输入和未经授权入口。本轮停在独立假执行器边界，不合并 main，不发布 APK。
