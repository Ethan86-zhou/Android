# ForceSense 力感知

ForceSense 是原生 Java / Android Canvas 应用，沿用“侧键透视”和“形变特写”两种 UI。手机光感传感器的真实光照数据驱动模拟按压力：遮挡越多，图中的按钮向内微移，细长传感器的弯曲越明显；移开遮挡后恢复。

应用包名：`com.forcesense.app`。版本：`1.0.0`。

## 安装与体验

主交付文件：[`outputs/ForceSense-1.0.0-debug.apk`](outputs/ForceSense-1.0.0-debug.apk)。这是便于本地安装和验证的 debug APK。

1. 安装并打开“ForceSense 力感知”，保持屏幕亮起。
2. 先露出手机的光感区域，在光照充足的环境中建立零点；当前设备可从手机正面上部附近开始查找遮挡位置，并观察实时 lux 是否下降。
3. 首次使用时，第一个不低于 `1 lx` 的有效读数自动设为零点。也可点击“以当前光照设为零点”，重新记录无遮挡环境光。
4. 用手指逐渐遮挡光感区域，观察“实时光照”、模拟强度条和传感器弯曲联动；移开手指可看到回弹。
5. 点击“形变特写”或“侧键透视”切换视图。环境光明显变化后，露出光感区域重新设定零点。

屏幕中的手机侧键与力传感器是受力路径示意；交互数据来自手机光感区域。`1.8 × 4 mm` 仅表示图中力传感器的外形尺寸，`4 mm` 表示长度，`1.8 mm` 未作为厚度。按压力以模拟百分比表示，形变为放大示意，应用没有输出实测牛顿或微米值。

## 数据与映射

应用获取 `Sensor.TYPE_LIGHT` 的 `event.values[0]`，原始单位为 lux（界面缩写 `lx`）。这一数据含义与 [Android 环境传感器文档](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment)一致。

设无遮挡基线为 `baselineLux`，当前光照为 `lux`：

```text
模拟按压力（%） = clamp(1 - lux / baselineLux, 0, 1) × 100
```

| 当前光照 | 模拟强度 |
| --- | --- |
| 等于或高于零点光照 | 0% |
| 零点光照的一半 | 50% |
| 实际收到 0 lx | 100% |

基线要求为有限且不低于 `1 lx` 的有效值；低于该值会提示光线不足。`0 lx` 是合法的实际光感读数，但不能用于建立基线。无事件、负数、NaN 或 Infinity 显示为等待/异常状态，不能当作实测零光照。失败的重新校准保留之前的有效基线。基线不会随遮挡自动降低，持续遮挡时模拟强度可保持。

UI 使用 `ValueAnimator` 在约 `180 ms` 内过渡到映射后的强度，数值、进度条和 Canvas 图示由同一个动画值更新。应用未修改原始 lux，也没有用定时生成的数字替代光感数据。

`onResume()` 注册监听，`onPause()` 注销监听并取消动画；返回前台后重新等待有效光感事件。实现遵循 [Android 传感器概述中的监听生命周期示例](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)。当前设备光感采用 on-change 上报：读数稳定时不一定持续产生事件，应用只检查首次等待，不把之后事件稀疏判为失效。

零点与视图选择通过本地 `SharedPreferences` 保存。运行快照保存在应用私有目录 `files/sensor_state.json`。应用未申请网络权限或外部存储权限。

## 工程环境

| 项目 | 配置 |
| --- | --- |
| 最低系统 | Android 8.0 / API 26 |
| compileSdk / targetSdk | 35 / 35 |
| Java 源码级别 | 17 |
| Gradle Wrapper | 8.10.2 |
| Android Gradle Plugin | 8.7.3 |
| UI | 原生 Activity / View / Canvas；无外部 UI 依赖 |

入口为 `app/src/main/java/com/forcesense/app/MainActivity.java`；`ForceDiagramView.java` 绘制卡片；`LightForceMapper.java` 是不依赖 Android 的映射类。`tools/LightForceMapperCheck.java` 提供独立映射验证。

Android 15 及以上、targetSdk 35 的应用需要处理 edge-to-edge 布局。本应用使用系统栏及显示缺口的 `WindowInsets` 留出安全区域，并通过 `ScrollView` 支持较小屏幕；相关规则见 [Android edge-to-edge 文档](https://developer.android.com/develop/ui/views/layout/edge-to-edge)。

## PowerShell 构建

在 Android Studio 打开本目录，安装 Android SDK Platform 35，并使用 JDK 17 或兼容的 Android Studio JBR。当前验证机器使用 Android Studio 自带的 JBR。

换电脑开发时，先按 [仓库根目录的跨电脑说明](../../README.md) 克隆、配置 Git 身份和提交检查，再在 Android Studio 打开本项目目录。Windows 使用 `gradlew.bat`；macOS / Linux 使用 `./gradlew`。首次构建需要联网下载 Gradle 和依赖。

`local.properties` 是本机 SDK 配置，已加入 `.gitignore`，新克隆不会包含它。由 Android Studio 配置本机 SDK，或创建 `local.properties` 并填入 SDK Manager 显示的实际路径。下面仅是 Windows 自定义 SDK 目录的示例，使用正斜杠可减少转义：

```properties
sdk.dir=D:/Android/Sdk
```

在项目目录运行：

```powershell
# JAVA_HOME 按本机 Android Studio 或 JDK 安装位置调整。
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon assembleDebug lintDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。交付目录中的 `ForceSense-1.0.0-debug.apk` 是 2026-10-08 已完成实机验证的安装文件副本。新电脑构建的 APK 可能使用不同 debug 签名，也可能具有不同哈希，应另外记录版本及验证结果，保留历史交付文件。

独立运行映射检查：

```powershell
New-Item -ItemType Directory -Path '.\tools\classes' -Force | Out-Null
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $javac -encoding UTF-8 -d '.\tools\classes' `
    '.\app\src\main\java\com\forcesense\app\LightForceMapper.java' `
    '.\tools\LightForceMapperCheck.java'
if ($LASTEXITCODE -ne 0) { throw '映射检查编译失败' }
& $java -cp '.\tools\classes' LightForceMapperCheck
if ($LASTEXITCODE -ne 0) { throw '映射检查失败' }
```

成功输出：`PASS: 43 LightForceMapper checks`。

## ADB 安装与检查

连接手机，在手机上启用 USB 调试，并允许当前电脑；换电脑需重新授权。以下使用 Windows 常见 SDK 目录，若安装在其他位置，修改 `$androidSdk`：

```powershell
$androidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $androidSdk 'platform-tools\adb.exe'
& $adb devices -l
& $adb install -r '.\outputs\ForceSense-1.0.0-debug.apk'
& $adb shell am start -n 'com.forcesense.app/.MainActivity'
& $adb logcat -d -s 'ForceSense:I' '*:S'
& $adb shell run-as com.forcesense.app cat files/sensor_state.json
```

连接多台设备时，在每条 ADB 命令中加入 `-s <设备序列号>` 指定目标。`run-as` 用于读取此 debug 应用的私有验证快照；应用自身不需要外部存储权限。

上面的安装命令使用仓库已有的交付 APK；安装本机新构建版本时改用 `app/build/outputs/apk/debug/app-debug.apk`。若因 debug 签名不同无法覆盖安装，先核对并使用相同开发签名，或由用户确认可清除数据后卸载重装；卸载会清除保存的零点和视图设置。签名密钥在仓库外管理，详见 [Android 签名文档](https://developer.android.com/studio/publish/app-signing)。

## 实机验证记录

验证日期：2026-10-08（北京时间）。应用已安装并运行在三星 `SM-S9080`，系统 Android 16 / API 36。实际识别的光感传感器为 `STK33915 Light Ambient Light Sensor Non-wakeup`，注册日志为 `LIGHT_REGISTERED=true`，`reportingMode=1`。

真实日志建立了以下响应链：

| 状态 | 实测光照 | 零点光照 | 日志中的模拟强度 |
| --- | --- | --- | --- |
| 初次自动建立零点 | 135.000 lx | 135.000 lx | 0% |
| 遮挡光感区域 | 6.008 lx | 135.000 lx | 95.55% |
| 移开遮挡 | 138.982 lx | 135.000 lx | 0% |
| 移开遮挡后的读数 | 139.995 lx | 135.000 lx | 0% |

日志保留了真实 lux，表内百分比采用日志显示精度。`43` 项纯 Java 映射检查通过，覆盖未校准、无效读数、0 lx、半基线、变亮、显式重校准与极端有限浮点数。`assembleDebug` 和 `lintDebug` 均通过；lint 为 `0 errors, 1 warnings`，唯一警告是 `DataExtractionRules` 备份配置提示，清单已设置 `android:allowBackup="false"`。

交付证据：

- [`outputs/verification/device-final-overview.png`](outputs/verification/device-final-overview.png)：最终安装版本的实机侧键透视截图。
- [`outputs/verification/device-detail.png`](outputs/verification/device-detail.png)：实机形变特写截图。
- [`outputs/verification/device-light-events.txt`](outputs/verification/device-light-events.txt)：光感注册、自动校准及真实事件日志。
- [`outputs/verification/verification.json`](outputs/verification/verification.json)：本次构建与设备验证汇总。

实时快照中的 `simulatedForcePercent` 是映射目标；屏幕经过短时动画后收敛到该目标。测试环境光与手机姿态变化都可能改变 lux，重新设定无遮挡零点即可在新环境继续体验。
