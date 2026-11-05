# G1-B1：安全通信与配对验证协议

日期：2026-11-05。实现范围为独立验证包 `com.pilipala.remoteprobe`，不是 Marco TV 的生产远控接口。**只连接假输入计数器，不调用 MarcoFocusController/HomeView。** 当前设备 TLS 可行性及人工配对补测状态见 [验证报告](G1_B1_REPORT.md)；早期只验证握手，不代表完整会话已验证。未经本阶段验收不得进入 G1-B2。

## 信任边界与实现来源

| 组件 | 实现与审查依据 |
|---|---|
| Android TLS | 系统 JSSE/SSLServerSocket；本验证固定 TLS 1.2、`TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`，不自行实现握手、加密或签名。[平台 API](https://developer.android.com/reference/javax/net/ssl/SSLServerSocket) |
| 设备身份 | [DeviceIdentity](../security-spike/src/com/pilipala/remoteprobe/DeviceIdentity.java)：AndroidKeyStore EC P-256 不可导出私钥，自签 X.509；生成时用随机 UUID 的 CN 标识设备。每次加载校验有效期和自签名，失败关闭，不静默换身份。[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) |
| Windows 验证 | [client.py](../security-spike/tools/client.py)：Python 标准库 ssl/OpenSSL；`CERT_REQUIRED`，独立 context 仅装入带外提供的证书，不加载公共根、不先信任网络返回的证书。[Python 3.13 ssl](https://docs.python.org/3.13/library/ssl.html) |
| 自签名检查 | OpenSSL `X509_V_FLAG_CHECK_SS_SIGNATURE`（0x4000）开启信任锚自签名检查；默认链校验检查时间，额外比对实际 leaf 完整 DER SHA-256 与 CN identity。[OpenSSL 参数说明](https://docs.openssl.org/3.0/man3/X509_VERIFY_PARAM_set_flags/) |
| 业务会话 | [SessionPolicy](../security-spike/src/com/pilipala/remoteprobe/SessionPolicy.java)：原创同步状态机，SHA-256 摘要与 SecureRandom 交给平台；不是自创密码学协议 |
| 传输/UI | [ProbeServer](../security-spike/src/com/pilipala/remoteprobe/ProbeServer.java)、[ProbeActivity](../security-spike/src/com/pilipala/remoteprobe/ProbeActivity.java)：单连接串行处理、本机明确批准、撤销关闭 socket；独立包没有生产桌面引用 |

本验证不使用 WSS：使用 TLS 上有界 ASCII 行协议，减少 spike 依赖。这是原架构的阶段性收窄，不决定未来产品传输。Android 9 系统 TLS 的安全补丁状态不因本次握手成功而获得长期背书，也没有宣称第三方安全审计通过。

Android 9 本机 Conscrypt 实际签名路径使用已摘要数据的 `NONEwithECDSA`。第一次仅允许 SHA 系列 digest 的密钥在握手时报 `Incompatible digest`；为 TLS 密钥增加 `DIGEST_NONE` 后成功，证书仍使用可验证的自签名。本轮工具输出记录了对应系统错误，摘录见证据摘要；不通过导出私钥、换明文或关闭证书验证规避问题。该权限允许应用调用原始 ECDSA 签名，私钥仅供本模块使用，不提供远程签名接口。

## 两条隔离路径

1. **配对/假输入路径**：只有电视本机“主动开启配对”按钮能调用 `openWindow()`；启动 Intent 无法代替该动作，也没有网络 APPROVE 命令。需 Activity 前台、窗口焦点和屏幕可交互。收到正确票据后显示批准/拒绝对话框，默认焦点是拒绝。批准只绑定当前 TLS 连接。
2. **开发诊断路径**：通过明确的 `tls_diagnostic` 开发启动参数可运行 60 秒的 TLS-only listener。握手后立即关闭连接，没有配对票据，没有命令解析，没有应用数据响应；允许屏幕不可交互时完成纯握手。它不是无认证控制接口，也不能作为已批准会话证据。该开发入口不得随产品发布。

APK 为 debug 独立测试包，只有 INTERNET 权限；无 HOME alias、无服务/receiver、无无障碍或输入注入。debug 是为了通过**既有已授权 ADB**读取应用私有的带外 bootstrap 文件。开发 ADB 主机属于本验证信任边界；ADB 不承担 LAN TLS 传输或批准动作。本实现不能声称已经完成消费者可用的扫码引导，未来必须移除 debug/ADB bootstrap 和诊断 Intent。

## 身份、证书固定和票据

- 私钥保存在独立应用 UID 的 AndroidKeyStore，Android 报告 `getEncoded()==null`。本机 KeyInfo 报告硬件支持，不等于独立硬件证明/attestation。进程重启保持证书；卸载/清数据导致身份变化，客户端必须重新带外信任，禁止按同 IP 自动接受。
- 证书有效期 1 年，notBefore 为生成时钟前 60 秒。系统时钟错误导致验证失败，不关闭时间检查；到期需单独设计显式轮换，本 spike 不静默轮换已保存身份。
- 票据 256 bit，120 秒，内存策略只存 SHA-256；正确票据首次提交立即消费，再进入最长 30 秒等待批准，且不能越过原票据到期点。5 次错误票据关闭窗口。拒绝、超时或响应丢失均重新在电视开启。
- Windows 从电视带外取得 PEM + 完整 pin + deviceId + LAN 地址/端口。生产应通过可信扫码等完成；本开发包将它们放在应用私有文件中，由既有 ADB 授权主机读取。完整 PEM 是信任锚，pin 是精确证书绑定；IP 只是拨号地址，因此不用 Web 的 IP hostname 匹配，而检查 pin 和证书 CN 的设备身份。
- `connect()` 顺序：验证 bundle 结构和 PEM/pin一致 → `CERT_REQUIRED` TLS 握手/证书有效期/自签名校验 → 实际 leaf pin → CN identity → 返回 socket。**只有此函数全部成功后，客户端才发送 `PAIR ticket`。** 没有 CERT_NONE、全局信任回调或网络首次证书自动收养。
- 临时 `pairing.json` 含票据，属于秘密：MODE_PRIVATE，pending/关闭/到期时删除，禁止上传。进程突然被杀时文件可能留存，但票据内存状态不恢复；下一次本地开启会覆盖旧文件。debug ADB 主机可读取，不能把此机制当成正式凭据保管方案。

## 会话状态与撤销

```text
CLOSED --电视本机开启--> WINDOW --正确票据/消费--> PENDING
PENDING --电视明确批准当前连接--> ACTIVE --关闭/断连/超时/撤销--> CLOSED
WINDOW/PENDING --拒绝/到期/暂停--> CLOSED
```

ACTIVE 发放独立 256-bit session ID，但服务端还检查**内部连接标识**；它不是可跨连接使用的长期 bearer credential。断线不重连恢复、不保存持久客户端凭据。再次连接需要新的电视配对窗口/票据/批准。即使 TLS 层恢复加密会话，也不能恢复应用授权。

会话绝对期限 5 分钟，15 秒无认证帧关闭；客户端维持会话可每 5 秒 PING。电视“拒绝/撤销”按钮同步撤销 policy，再由独立后台线程关闭 listener/current socket；Activity 暂停也走同一流程。Android9 的 TLS close_notify 会执行网络 I/O，不能从 UI 线程直接调用 SSLSocket.close()。本轮人工真机补测发现并修复了该问题；BackgroundClose 的回归测试检查即时撤权、后台 I/O 与并发幂等。定时器周期 500ms 清理闲置 socket，但每次执行/批准都同步校验截止时间，因此不会把定时器延迟当作授权延长。

单 listener、同时一个握手/连接、TCP backlog=1，无应用工作队列；握手最多 5 秒，首次 frame 总期限 5 秒，后续 frame 总期限 15 秒。单 frame 最多 4096 个 ASCII 可打印字符 + LF，拒绝 CR/非 ASCII、控制字符与超长帧。每秒最多 10 个认证 frame（包含 PING/快照）；超限撤销。此限制是 spike 的资源上界，仍可能被同网攻击者短时间占用唯一连接造成拒绝服务，不声称完成产品级 DoS 防护。

## 线协议与假执行器

```text
客户端: PAIR <64hex ticket>
服务端: PENDING
本机批准后: GRANTED <64hex sessionId>
后续请求: <sessionId> <严格递增seq，从1开始> <verb> [参数]
```

| verb | 参数/响应 | 执行边界 |
|---|---|---|
| PING | 无参数 / PONG | 更新空闲期限，不延长绝对会话期限 |
| SNAPSHOT | 无参数 / SNAPSHOT nonce fakeCount | 新发 256-bit challenge，1000ms 有效；替换旧 challenge |
| STEP | nonce UP/DOWN/LEFT/RIGHT | 只把假计数 +1，返回 FAKE_APPLIED；不对应真实卡片 |
| CLOSE | 无参数 / CLOSED | 撤销当前授权，关闭连接 |

每个认证 frame 必须是前一 seq+1；重复或乱序立即返回 REPLAY 并撤销，而不是自动重发。即使语义失败也消费 seq。challenge 只用一次、执行时检查服务端单调时钟截止，失效返回 STALE_CHALLENGE。ACK 丢失不自动重试，先关会话再人工重新批准。没有 long press、keycode、包名、Intent、文件、shell、文本或坐标命令。

不记录票据/session/challenge/命令参数。验证日志仅保留结果类型、TLS版本/套件、密钥属性和时间。带外文件、测试私钥和完整地址只留被忽略的 private/build；公开证据去掉这些信息。

## G1-A 控制器接网审查

已读 [MarcoFocusController](../app/src/main/java/com/pilipala/tvhome/MarcoFocusController.java)，本轮不修改它。

| 风险/依据 | G1-B2 必须满足的条件 |
|---|---|
| `prepare()` 的 TTL 从调用时 `now()` 开始。如果网络先排队、后在 UI 调用，会让陈旧命令得到新 TTL | 在接收/快照时保存服务端单调截止时间，UI 执行前再核对，禁止从出队时间重新计时。当前挑战有效期在发快照时开始，不能直接套成新 prepare TTL |
| Snapshot 私有 owner 与 revision 来自进程内对象，不能直接从客户端反序列化；网络拿旧 revision，却由适配器现场重新 snapshot，会掩盖冲突 | 将 challenge 绑定服务器保存的真实 Snapshot/epoch/layoutRevision/focusRevision，执行时用同一个对象，不“刷新后再执行旧动作” |
| 网络线程与控制器 ownerThread 不同，`disconnect()` 也要求 ownerThread；仅往 UI 排队撤销可能落后于旧动作 | 会话先在安全域原子撤销，再 UI cancel；UI 提交前同时检查当前授权 generation 和快照期限，不只检查控制器 epoch |
| `execute()` 先更新领域状态，之后 HomeView 渲染可能失败 | 同一 UI 回调完成受限状态提交；失败作废会话/快照，不能报告可见成功；UI ACK 与实际画面证据分开 |
| 本地按键、触摸、布局加载/同尺寸重排、弹窗栈变化可能没有桥接 | 枚举全部入口；本地同位置事件也递增 focusRevision，布局替换递增两个 revision；对话框状态使用聚合值 |
| 会话截止、挑战期限、控制器 lease 是三个时效来源 | 有效截止取最早值；队列取消不延长任何期限；客户端自报时间不可信 |
| G1-A 单次 Command 对象不是网络去重 | 传输 seq/challenge 先校验，再创建一次域操作；结果丢失不重放。业务必须另加真实 revision 绑定 |

本轮完全隔离假执行器，故以上风险没有通过桥接实际触发桌面，但也**尚未被证明在 G1-B2 集成中解决**。不得因本次 TLS/策略测试通过跳过该阶段验收。

## 许可与依赖

所有新增源码/测试在 MarcoTV 下为原创 GPL-3.0-only，没有拷贝六个研究仓库的实现。Android 使用平台 TLS/KeyStore；Windows 运行客户端只依赖 Python 标准库。测试生成证书用既有 G0 审核的 `cryptography==50.0.2`（Apache-2.0 OR BSD-3-Clause），仅开发环境，不进入 APK。无新产品第三方库；未进行整个系统镜像、OpenSSL/JDK 或传递依赖的完整 SBOM/漏洞审计。正式分发必须另审。
