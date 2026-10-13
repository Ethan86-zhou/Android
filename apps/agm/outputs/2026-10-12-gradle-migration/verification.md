# AGM Gradle 迁移验证 · 2026-10-12

本记录针对本目录的 `IMU-Flight-Lab-1.0-debug.apk`，不沿用历史 APK 的真机结论。

## 来源与产物

- 仓库基线：`b832f09106bfd3b88a119dfb5a89536120f2cc8f`，`main`。
- 集成分支：`feature/agm-import`。本次导入是未提交的工作区修改，未推送；上述基线提交尚不包含 AGM。
- [source-manifest.json](source-manifest.json) 列出实际参与本次构建的源码、资源、测试和构建配置的 SHA-256，不包含本机路径及签名配置。
- APK SHA-256：`0fcc7901db6dbf1f892054e2758cf0270de097092f616660b7b983fb0716d9b9`。
- 迁移前手工构建 APK SHA-256：`2d55e3b036f259a736dcce2540a5e544ef61f73f2792137076c31432a6819bc8`。
- 两者签名证书 SHA-256 均为：`0fd08d58a4cf76c698d757a56cb936d007f0f435e4e13b068185cabc70393c3e`。
- 包名 `com.agm.blank`、入口 `com.agm.blank.MainActivity`、版本 `1.0`（code 1）、minSdk 23、target/compileSdk 34 保持一致。
- Java 源码、四个界面资源与原项目逐字节一致；manifest 仅迁移 Gradle 管理的 SDK、版本、namespace 和 debug 配置，并将应用名称提取为字符串资源。

## 检查结果

在应用目录通过项目 Wrapper 执行：

```text
gradlew.bat assembleDebug testDebugUnitTest lintDebug :app:testSensorUi
```

本机 Node 不在标准 PATH 时通过 `-PnodeExecutable` 指定。Java 使用 JBR 17.0.6；Gradle 8.10.2，AGP 8.7.3。首次构建遇到 JBR 的 Unix domain socket 临时路径错误，通过本机 `jdk.net.unixdomain.tmpdir` 环境参数解决，未把绝对路径写入版本化配置。

| 检查 | 结果 |
| --- | --- |
| assembleDebug | 通过，Gradle BUILD SUCCESSFUL |
| JUnit | 7 项，0 失败，0 错误，0 跳过 |
| 页面数据绑定与桥接 | 通过 |
| lintDebug | 0 错误、4 条提示 |
| 仓库 check-repository.py --local | 通过，覆盖包含 AGM 的 5 个 App |
| git diff --cached --check | 通过 |
| APK 签名校验与证书对照 | 通过，新旧证书一致 |
| APK ZIP / classes.dex / assets | 通过，四个 assets 与源码一致 |
| 真机安装和传感器运行 | 未执行；ADB 未检测到连接设备 |

Lint 提示保留记录，未压制：固定横屏（DiscouragedApi，用户要求）、本地界面启用 JavaScript（SetJavaScriptEnabled，界面必需）、Android 12+ 备份规则（DataExtractionRules，继承原配置）、尚未提供应用图标（MissingApplicationIcon，继承原项目）。对应可移植文本报告见 [lint-results-debug.txt](lint-results-debug.txt)。

## 后续真机验证

连接并授权手机后，用此 APK 覆盖安装，不卸载旧 App。确认横屏、ACC m/s²、黄色纯 gyro 相对零点、青色融合姿态、双航向、两个独立归零按钮及气压基准仍正常。此次签名对照证明具备覆盖安装条件，但不能代替实际设备验证。

本机 `local.properties`、`signing.properties` 和原调试密钥均被排除在 Git 之外；未改动其他 App 的历史源码或交付证据。
