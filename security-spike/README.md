# Marco TLS 安全验证包（G1-B1）

独立 debug 应用 `com.pilipala.remoteprobe`，不修改 Marco TV 安装，不进入 launcher 构建。仅假输入计数。源码 GPL-3.0-only。**未完成人工电视批准/主动撤销验收，不可发布。**

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
