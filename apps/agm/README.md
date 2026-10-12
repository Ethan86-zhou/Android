# IMU Flight Lab（AGM）

横屏、离线的 Android 手机传感器演示应用。位于仓库 `apps/agm/`，是可单独在 Android Studio 中打开的 Gradle 工程；应用包名保持 `com.agm.blank`。

开发时同时遵守仓库根目录的 [AGENTS.md](../../AGENTS.md) 和 [README](../../README.md)。本目录不创建独立 Git 仓库。

## 仪表与操作

- **黄色飞机 / ROLL、PITCH**：只由三轴陀螺仪积分更新。启动及 **ZERO GYRO** 都以单位矩阵作为初值，不使用加速度或磁力计；手机倾斜放好再启动，黄色飞机仍从中心零位开始。转动后保持相对姿态，可能缓慢漂移。
- **青色飞机 / FUSION**：使用 Android Rotation Vector 系统融合姿态，以地面为参考；与黄色飞机分别计算、分别显示。系统融合数据不可用时隐藏青色飞机。
- **顶部 MAG**：低通后的加速度与磁力计计算倾斜补偿磁航向。**顶部 GYRO**：用磁姿态初始化，此后独立积分三轴陀螺角速度。它与黄色飞机是两个独立积分器。
- **ALIGN HEADING**：只重新对齐顶部 GYRO 的磁参考初值，不影响黄色飞机。右侧角差为 GYRO − MAG，按最短角差显示（−180° 至 180°）。屏幕上边缘接近竖直、水平投影不足时，航向显示空值。
- **SET GROUND 0**：将当前平滑气压保存为地面基准；未手动设置时使用本次会话首次有效气压。高度是相对气压高度，会受气压环境变化影响。
- 横屏坐标为 X 向右、Y 向上、Z 朝屏幕外。ACC 为含重力加速度，单位 **m/s²**，静止时重力约 9.8 m/s²；GYRO 为 **°/s**；MAG 场强为 **µT**；BARO 为 **hPa**；相对高度为 **m**。
- 底栏显示实际采样率。AGM 请求约 100 Hz，气压请求约 25 Hz，界面约每 33 ms 更新。两秒无传感器数据时显示空值；后台停止监听。恢复前台或陀螺采样间断超过 250 ms 时，黄色飞机重新归零、顶部陀螺航向重新等待磁姿态初始化。
- `MAG: FIGURE-8` 表示磁精度较低，可远离磁性物体后做八字运动。磁航向可能受到磁场和线性加速度影响，纯陀螺积分会随时间漂移。

## 开发与构建

使用 JDK 17、Android SDK 34（compileSdk/targetSdk 34，minSdk 23）。工程采用 Android Gradle Plugin 8.7.3、Gradle Wrapper 8.10.2，Java 源码兼容级别为 8。无需单独安装 Gradle；首次构建需要下载构建依赖。

在 Android Studio 中打开 `apps/agm/` 并选择 JDK 17；或配置本机 `JAVA_HOME` 和 Android SDK。SDK 路径可写入不提交的 `local.properties`（`sdk.dir`），也可通过本机 Android SDK 环境变量配置。

以下命令在 `apps/agm/` 目录运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

有 Node.js 时，还可验证页面数据绑定和按钮桥接：

```powershell
node tests/sensors.test.cjs
```

也可通过 `gradlew.bat :app:testSensorUi` 运行页面测试；Node 不在 PATH 时用 `-PnodeExecutable=<本机 Node 路径>` 指定。

若 Windows 的旧版 JBR 17 出现 `Unable to establish loopback connection` / `UnixDomainSockets.connect`，本次构建通过为 Java 设置 `-Djdk.net.unixdomain.tmpdir=<本机已有的短路径临时目录>` 解决。该参数仅用于本机环境，不把绝对路径写入提交的 Gradle 配置。

JUnit 测试覆盖 90° 积分、三轴旋转组合、磁初值独立性、采样间断恢复、相对归零及两个积分器的独立性。页面测试覆盖双色飞机各自的数据来源、航向角差、归零按钮、单位及传感器缺失状态。

主要输出：

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`

旧手工构建脚本不再作为本工程的构建入口。应用源码和离线资源位于 `app/src/main/`，Java 单元测试位于 `app/src/test/java/`，页面测试位于 `tests/`。

## 调试签名与覆盖安装

默认使用当前开发机的 Android 调试签名。不同机器的默认调试证书通常不同；即使包名相同，也不能用不同证书直接覆盖已安装版本。

需要保持原版签名时，在 `apps/agm/` 下创建**不提交的** `signing.properties`，提供以下四个字段：

- `storeFile`：原调试密钥文件路径；相对路径以 `apps/agm/` 为基准，也可使用仅适用于本机的绝对路径。
- `storePassword`：密钥库密码。
- `keyAlias`：签名密钥别名。
- `keyPassword`：签名密钥密码。

原调试密钥可以保留在仓库外，由本机配置引用。不要提交密钥、密码、`signing.properties`、`local.properties`、缓存或普通构建临时产物；有意保留的交付 APK 放入独立的 `outputs/` 日期目录。覆盖安装保留 `com.agm.blank` 的应用数据及保存的气压基准；使用不同证书前若卸载旧应用，原应用数据会丢失。签名不匹配时先核对密钥，不能为解决安装失败自动卸载应用。

手机连接并允许 USB 调试后，可执行：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.agm.blank/.MainActivity
```

## 迁移验证记录

本次交付目录为 `outputs/2026-10-12-gradle-migration/`，保存新 APK 和 `verification.md`，不覆盖旧验证记录。记录中应包含 APK SHA-256、源码基线提交以及构建时未提交源码的快照清单。

迁移基于仓库提交 `b832f09106bfd3b88a119dfb5a89536120f2cc8f`，源码导入提交为 `f39b5af88719b12c70fb2994646ecafc90ad9f58`，工作分支为 `feature/agm-import`。首次构建时的源码快照与验证记录原样保留，后续验证使用独立目录。

已通过 Gradle debug 构建、7 项 JUnit 测试、页面测试和仓库结构检查。Lint 为 0 错误、4 条提示，详见 [构建验证记录](outputs/2026-10-12-gradle-migration/verification.md)。新旧 APK 的签名证书一致。

2026-10-12 已在三星 SM-S9080 / Android 16 上对同一交付 APK 完成覆盖安装、数据保留、实时传感器、归零/对齐按钮、前后台恢复及冷启动检查，详见 [独立真机记录](outputs/2026-10-12-device-validation/verification.md)。本轮未进行有角度基准的物理转动或高度精度测量。

实现参考：[Android SensorManager](https://developer.android.com/reference/android/hardware/SensorManager)。
