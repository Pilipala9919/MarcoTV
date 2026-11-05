# Marco TLS 安全验证包（G1-B1）

独立 debug 应用 `com.pilipala.remoteprobe`，不修改 Marco TV 安装，不进入 launcher 构建。仅假输入计数。源码 GPL-3.0-only。**已完成报告所列的真实电视人工会话验证，等待 G1-B1 审查；不可发布，不接真实焦点。**

- [协议与状态一致性审查](../docs/G1_B1_SECURITY_PROTOCOL.md)
- [实际验证结果](../docs/G1_B1_REPORT.md)

先按 MarcoTV 构建说明配置 `.tools/toolchain.json`：

```powershell
python security-spike/tools/test_policy.py
python security-spike/tools/build.py
```

APK 在 `build/security-spike/MarcoTlsProbe-local.apk`，不在 dist，CI 不上传它。独立签名在 `private/security-spike`；绝不复用正式签名。主机 TLS 测试需 G0 独立环境的 cryptography 50.0.2：

```powershell
python security-spike/tests/test_client.py
# MARCO_TEST_BIND 为 Windows 本机实际拥有的 LAN IPv4，不是电视地址。
python security-spike/tests/test_transport.py
```

`tests/host` 含自动批准的测试夹具，build.py **只编译 src**；不得把 host 测试当成真实电视批准。

真实握手诊断：显式安装本地验证 APK 后，设置 `$adb`/`$serial`，准备一个与电视无关的有效测试证书 `untrusted.pem`。可以用 tests/test_client.py 的 certificate() 生成，不能拿正式私钥：

```powershell
python security-spike/tools/probe_device.py --adb $adb --serial $serial --untrusted-pem private/security-spike/untrusted.pem --output private/security-spike/device-report.json
```

诊断不产生配对票据或应用指令；通过已授权 ADB 读取带外信任包，实际 TLS 经局域网。退出后 force-stop 验证包，保留安装供后续本机操作，不动 Marco TV。

人工配对验收时：从电视启动验证程序，选择 LAN 地址，实体遥控器点击“主动开启配对”。通过已授权 ADB 把应用私有 `files/pairing.json` 读到 Windows 被忽略的 private 路径；这是开发 bootstrap，不是生产扫码。不得在终端输出、提交或聊天发送票据文件内容。

```powershell
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --hold 60
```

Windows 显示 certificate_verified 后才发送票据，电视显示请求；本机明确批准后客户端只增加假计数。在 hold 期间按电视撤销按钮，预期连接立即结束；拒绝时不应 GRANTED。每次重测重新在电视开启窗口、读取新票据。`--negative replay/stale/disconnect/idle` 分别检验重复序号、过期挑战、断连及空闲到期；人工与真实网络测试的结果单独归档。

## 重复人工测试与采集

每轮使用一个新窗口和新的带外文件，电视批准必须由实体遥控完成。拒绝一轮之后，再分别运行以下命令（每条是一轮，不同时启动）：

```powershell
# 批准后假计数 +1，留出本机按撤销按钮的时间。
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --hold 60
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --negative replay
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --negative stale
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --negative disconnect
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --negative idle
# 批准后每 5 秒保活，服务端应在 300 秒绝对上限关闭。
python security-spike/tools/client.py --bundle private/security-spike/pairing.json --hold 360
```

撤销、idle 和绝对期限测试的客户端可能以 EOF/连接异常非零退出；必须结合电视 LOCAL_REVOKE、SocketTimeoutException、LISTENER_CLOSED 和时间判断，不能仅把任何异常当作通过。最后一轮不要按返回/Home；批准后窗口暂时保持亮屏，到期恢复。若提前出现 LIFECYCLE_PAUSE，只能记录暂停取消，不能算绝对期限通过。

Windows 为每行输出加时间并保存（将上述所需模式替换到命令中）：

```powershell
New-Item -ItemType Directory -Force private/security-spike/manual | Out-Null
python -u security-spike/tools/client.py --bundle private/security-spike/pairing.json --hold 360 2>&1 |
  ForEach-Object { '{0} {1}' -f (Get-Date -Format o), $_ } |
  Tee-Object -FilePath private/security-spike/manual/windows.txt
& $adb -s $serial shell run-as com.pilipala.remoteprobe cat files/events.txt |
  Set-Content -Encoding utf8 private/security-spike/manual/tv-events.txt
& $adb -s $serial logcat -b crash -d -v epoch |
  Set-Content -Encoding utf8 private/security-spike/manual/crash.txt
Get-FileHash -Algorithm SHA256 build/security-spike/MarcoTlsProbe-local.apk
```

先后保存各轮电视事件边界、代码提交、APK 哈希与用户操作；旧 crash buffer 不等于本轮崩溃，必须核对时间。测试报告的精确 301.068 秒来自本地包装器的单调时钟；上述 PowerShell 是复测墙钟采集方案，不承诺同等计时精度。公开前仅保留必要结果类型，去除地址、票据、session、challenge 和非本应用日志；不可上传 `pairing.json`、私钥、debug 签名或完整设备日志。

当前实测与失败修正详见报告。120 秒票据自然到期、30 秒等待批准到期等仍仅有自动化证据，未标为真机通过。测试结束无需保持页面；没有开放窗口时不监听控制请求。
