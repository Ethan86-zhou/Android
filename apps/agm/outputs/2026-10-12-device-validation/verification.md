# AGM 真机验证 · 2026-10-12

本轮使用首次 Gradle 迁移交付的同一 APK，新增独立证据，不覆盖首次构建时的记录。

## 对应版本

- 源码提交：`f39b5af88719b12c70fb2994646ecafc90ad9f58`。
- APK：[IMU-Flight-Lab-1.0-debug.apk](../2026-10-12-gradle-migration/IMU-Flight-Lab-1.0-debug.apk)。
- APK SHA-256：`0fcc7901db6dbf1f892054e2758cf0270de097092f616660b7b983fb0716d9b9`。
- 通过设备 `pm path` 定位已安装的 base.apk，设备端 SHA-256 与上述哈希一致。
- 包名 `com.agm.blank`，版本 1.0 / code 1；设备三星 SM-S9080，Android 16 / API 36。
- 横屏显示 3088 × 1440。截图仅含测试应用，未保存设备序列号或其他应用信息。

## 测试结果

| 项目 | 操作及观测 | 结果 |
| --- | --- | --- |
| 覆盖安装 | `adb install -r` 成功；无卸载；安装前后 groundPressure 均为 1015.82 hPa | 通过 |
| 冷启动 | Activity 启动状态 ok，首次冷启动 1182 ms；最终再次冷启动 606 ms | 通过 |
| 横屏及离线界面 | 系统报告 LANDSCAPE，App 保持前台，双色飞机及三侧读数正常 | 通过 |
| 实时传感器 | 系统注册 ACC、GYRO、MAG、BARO、Rotation Vector；画面实测 ACC/GYRO 103–104 Hz、MAG 99–100 Hz、BARO 24–26 Hz | 通过 |
| ACC 单位 | 画面单位 m/s²，静置 Z 分量约 +9.84 至 +9.87 | 通过 |
| ZERO GYRO | 点击后黄色 ROLL/PITCH 显示约 0.0°，青色融合仍为 R +0.9° / P −0.3° | 通过 |
| ALIGN HEADING | 点击后显示 ALIGNED，截图角差 −0.6°；黄色飞机仍保持零位 | 通过 |
| SET GROUND 0 | 从约 +28.4 m 归至约 0.0 m，显示 GROUND SET；保存基准变为 1012.3975 hPa | 通过 |
| 后台监听 | 返回桌面，sensorservice 中本应用活动连接数由 1 降为 0 | 通过 |
| 回到前台 | 活动连接恢复为 1；下一统计窗口恢复实时采样率；黄色相对姿态重新归零 | 通过 |
| 基准持久化 | 切换前后台及 force-stop 后冷启动，groundPressure 仍为 1012.3975 hPa | 通过 |
| 应用错误 | 检查本 App 进程日志，未匹配到 FATAL EXCEPTION、Uncaught 或 JavaScript Console Error | 通过 |

按钮测试更新了本机气压基准，结束时应用保持运行。未改变其他传感器算法或构建输入。

本轮属于安装、静置读数、交互和生命周期的真机检查；未使用转台或高度基准测试角度准确性、快速旋转响应、磁干扰或长期漂移。已有 7 项 JUnit 和页面回归测试通过，不能替代这些未执行的物理精度测试。

## 截图

- [01-live.png](01-live.png)：覆盖安装后横屏实时读数。
- [02-gyro-zero.png](02-gyro-zero.png)：黄色归零、青色保留融合姿态。
- [03-heading-aligned.png](03-heading-aligned.png)：航向对齐反馈。
- [04-ground-zero.png](04-ground-zero.png)：气压高度归零反馈。
- [05-resumed.png](05-resumed.png)：回前台的首次画面，采样率统计窗口尚未满，显示 0 Hz。
- [06-resumed-stable.png](06-resumed-stable.png)：后续统计窗口恢复 ACC/GYRO 104 Hz、MAG 100 Hz、BARO 25 Hz。

首次构建检查、APK 签名、Lint 四项提示见 [构建验证记录](../2026-10-12-gradle-migration/verification.md)。
