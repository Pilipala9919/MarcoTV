# G1-B1：Android 9 TLS 与配对验证报告

日期：2026-11-05。分支 `codex/g1-b1-secure-session`，基于已审查的 G1-A `8ab4ec1dc0c82d1abb1c0b1662128359a19955d9`。没有修改 main 或稳定版安装；开发验证 APK 为独立包、独立签名。

## 当前判定

**AndroidKeyStore + 平台 TLS 与 Windows 严格证书验证已取得真实设备成功证据。完整“电视主动开启→明确批准→假输入→主动撤销”尚未真机验收，因此 G1-B1 整体未通过，不能进入 G1-B2。** 用户本轮明确表示暂时不方便在电视旁操作，继续完成可自动验证部分；没有用自动点击/ADB 按键代替本机授权。

已实现独立配对与会话代码及 Windows 客户端，命令仅接假计数器；自动批准只存在于主机测试夹具。独立 APK 的 DEX 已检查不含 MarcoFocusController、HomeView 或 TransportFixture。

| 证据层 | 结果 | 实际覆盖 |
|---|---|---|
| C：源码 | 完成 | 设备身份、完整 pin、短期单次票据、本机批准、连接绑定临时会话、seq/challenge、超时/撤销；见 [协议](G1_B1_SECURITY_PROTOCOL.md) |
| T-policy | 24 场景 / 126 断言通过 | 合成时间与事件，含票据/批准/会话过期、重放、拒绝、撤销、并发配对、连接所有权 |
| T-client | 11 测试通过 | 实际主机 loopback TLS，合成证书；证书错/过期/未来/坏签名/身份错误时未发送票据；帧长度/EOF/期限 |
| T-transport | 8 测试通过 | 实际 Java ProbeServer + SessionPolicy + Windows 客户端；主机夹具自动批准/拒绝，假输入、重放、过期 challenge、半帧超时、关闭/断线 |
| B：Android | 独立验证 APK 构建、签名/对齐、安装通过 | API28 编译、D8 min23；正式 Marco TV 源码和 Manifest 未变；没有发布 APK |
| D-TLS：真实电视 | 6 项检查通过 | 两次正确握手、错误 pin、错误信任根、错误设备身份、重启后身份保持 |
| D-pair/session | **未执行** | 电视实体遥控主动开启、明确批准/拒绝、批准后假输入、电视按钮撤销、真实会话重放/过期/断线待测 |

原有全量 17 个离线套件本轮重新通过（25.47 秒），包括 G1-A。它们不能替代上表 D-pair/session；本轮的 Android 构建结果专指独立 probe，不把旧 launcher APK 的构建时间伪称为新构建。

## 真机与 Windows 记录

可公开原始结构化摘要：[g1-b1-tls-20261105.json](evidence/g1-b1-tls-20261105.json)。摘要含本轮源码 SHA-256、已安装 APK 哈希、去地址设备信息、事件类型、Windows runtime 与测试结果。原始信任包/地址留在 `private/security-spike`，不上传。

- 型号 MiTV-ASTP0；Android 9/API28；固件 `22.8.30.1385`。Windows Python 3.13.13 / OpenSSL 3.0.19。
- 最终归档运行起点：**2026-11-05 19:06:20 北京时间**（日志为 UTC 11:06:20；电视事件使用自身 wall clock，不当成跨机精确延迟测量）。
- APK 本地与已安装包 SHA-256 一致：`4fc286775712be27dbd2368bd60c1d0087ce0a16f2328cf366ec9f9b429d300f`。
- AndroidKeyStore 已生成并加载 EC 身份；`getEncoded()==null`，KeyInfo 报告 `isInsideSecureHardware=true`。这是 API 实测，不是独立远程硬件认证。
- Windows `connect()` 成功返回，TLSv1.2 / `ECDHE-ECDSA-AES128-GCM-SHA256`；Android 同时记录 `TLS_OK TLSv1.2 TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`。
- Windows 错误 pin 在拨号前失败；错误信任根在真实 TLS 握手阶段被拒绝；错误 deviceId 在证书验证后、任何应用发送前被拒绝。这三项应用层发送字节均为 0，不能称为拒绝了已发送的输入。
- 两次 force-stop/restart 后重新带外读取身份，pin 相同，并再次握手成功。只证明进程重启持久性，不证明重启电视/卸载后仍保留身份。
- 测试结束已 force-stop 独立验证包；最终记录为无进程、端口不再可连接。首次立即检查退出端口时曾短暂仍能连接，改为有界等待并检查进程退出后通过；**它是开发清理记录，不是电视用户主动撤销成功证据**。

真实 TLS 连接经 LAN 直接建立，没有 `adb forward/reverse`，ADB 仅用于安装、启动无输入诊断、获取带外证书和采集日志。所有真机诊断均无票据、无应用命令、无批准。不会把“加密且确认服务器身份”描述为“双方控制会话已获授权”。

## 发现的问题与处理

1. **密钥用途兼容性**：初次密钥只允许 SHA 摘要，真实 Conscrypt 报 `Incompatible digest`，握手失败。补充用于 TLS 已摘要签名的 DIGEST_NONE，生成新的独立试验 alias 后成功；未绕过 KeyStore、未导出私钥、未改成信任所有证书。旧 alias 只在测试包私有 KeyStore 中，未曾获控制授权。
2. **电视非交互状态**：Activity 可能立即暂停。正常配对仍在 onPause 撤销；握手专用诊断允许在 60 秒上限内完成，不解析任何应用数据。没有放宽批准路径的前台/交互要求。
3. **重启后旧带外文件**：外层脚本曾在新 listener 建立前读到旧端口，出现拒绝连接。复测脚本先删除本验证包的确切旧诊断文件，再等待新文件；不把失败连接标为成功。
4. **新旧服务器取消竞争**：每次开启使用独立 SessionPolicy 实例，旧 listener 的 finally 不能撤销新 listener 的策略。人工 UI 生命周期整链仍需真机复核。
5. **网络与 G1-A 一致性**：prepare TTL 起算点、旧快照被刷新掩盖冲突、跨线程撤销延迟等风险已逐项记录在协议中。此次无生产适配器，未修改 G1-A 控制器，也未声称这些集成风险已被真机消除。

## 人工真机剩余验收

操作步骤见 [验证包 README](../security-spike/README.md)，不要将票据发到聊天或提交仓库。设备上的验证包现已停止；下一次需从电视本机开启。

| 步骤 | 必须保存的证据 | 成功条件 |
|---|---|---|
| 本机开启 120 秒窗口 | 电视操作记录、LOCAL_PAIR_WINDOW_OPENED | 默认无配对；只能本机开启 |
| Windows 提交正确票据但未批准 | certificate_verified、PENDING、电视提示 | 假计数不变，不出现 GRANTED |
| 拒绝/等待批准到期 | 本机拒绝或超时、客户端终止 | 不发 session，旧票据不可重用 |
| 重新开启并明确批准 | LOCAL_APPROVE、GRANTED、FAKE_APPLIED 1 | 只有假计数变化，真实桌面不动 |
| hold 时按电视撤销 | LOCAL_REVOKE、客户端断开、旧票据/会话拒绝 | 凭据不可恢复；需要新窗口和新批准 |
| replay/stale/idle/disconnect | 各次独立批准后的 Windows/电视对应日志 | 重放撤销；过期 challenge 不计数；超时/断线注销 |
| 暂停/离开验证 App | 前后台真实操作与断开日志 | 不在后台保留可操作会话 |

本轮不继续修改真实桌面，不引入 iOS、跨应用控制、鼠标或手柄。**只有这些 D-pair/session 项通过审查，才进入用户本轮定义的 G1-B2（安全会话接入 HomeView/MarcoFocusController）。**
