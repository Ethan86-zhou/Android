# 拾词 Lexi

为三星手机开发的离线英汉词典。包名 `com.ethan.lexi`，版本 `1.0.0`。使用原生 Java / Android View，最低 Android 11，目标 Android 16；主要适配三星 SM-S9080。

## 日常使用

- **查词**：输入英文即可显示精确词条、词形还原和前缀候选；查看中文释义、英文释义、音标、考试标签及词形。支持忽略大小写和首尾空格。
- **快捷入口**：长按桌面“拾词”图标选择“快捷查词”，直接打开键盘；首页可主动粘贴剪贴板文字。其他应用支持标准 Android 文字选择操作时，可选中文字后选择“拾词”；也支持系统分享纯文本到拾词。
- **单词本**：词条页点“加入单词本”，可添加笔记、标记掌握、按文字或学习状态筛选；收藏在本机 SQLite 数据库保存，重启后保留。
- **离线导出**：单词本的“导出全部”或设置中的“导出单词本”，通过系统文件选择器保存到“下载”等本机目录。CSV 供 Excel / 表格打开，Markdown 供阅读，JSON 用于完整备份和恢复。导出始终包含全部单词，不受当前筛选影响。
- **恢复备份**：设置 → 从 JSON 备份恢复，选择拾词导出的文件。合并单词；重复单词的笔记、学习状态和时间使用备份中的内容，恢复前有说明。

应用不申请网络或存储权限，无账号、广告和后台联网。首次打开会将安装包内的压缩词库校验并解压到手机本地，需要数秒和额外存储空间。选词查词是否出现在菜单中取决于来源应用是否支持标准操作。

卸载会删除本机单词本，换机前请导出 JSON。CSV 采用 UTF-8 BOM、带引号字段和 CRLF，可正确保存中文、逗号、引号和多行笔记；以公式触发符开头的文本会加保护前缀，完整原文请以 JSON 备份为准。JSON 恢复限制为 32 MiB / 50,000 条，完整校验后在事务中合并，异常文件不会留下部分导入。

## 词库来源

内置 [ECDICT](https://github.com/skywind3000/ECDICT) 基础英汉 CSV，保留全部词条。源数据固定到具体 Git 提交，原始 CSV 下载到被 Git 忽略的 `build/dictionary-source/`，不作为应用运行依赖。来源、源文件及数据库哈希、词条数和验证结果见 `app/src/main/assets/dictionary-source.json` 和 [DATA.md](DATA.md)。

上游 MIT 许可证随应用保存在 `assets/licenses/ECDICT-MIT.txt`，可在设置中查看。词条内容按上游提供，部分词条的释义或音标可能为空；本应用未添加在线发音或自动翻译。

## 构建与复核

使用本项目独立 Wrapper：Gradle 8.11.1、Android Gradle Plugin 8.9.1、JDK 17 或兼容 JBR、Android SDK 36 / Build Tools 36.0.0。本机 SDK 路径通过不跟踪的 `local.properties` 配置，先阅读[仓库规则](../../AGENTS.md)，不需要全局 Gradle。

```powershell
.\gradlew.bat --no-daemon assembleDebug lintDebug assembleDebugAndroidTest
```

重新生成词库使用 `tools/build_dictionary.py`；随仓库提供的 `dictionary.pack` 是 gzip 压缩数据，保留 `.pack` 扩展名与 `noCompress 'pack'` 设置以避免打包工具自动解压或改名。构建后的应用可直接使用词库资源，无需在手机联网下载。设备验收使用独立测试 APK：

```powershell
python tools/device_acceptance.py --output outputs/新的验证目录
```

验收目录保存安装结果、查询性能、单词本持久化/导出恢复检查、设备截图、运行日志、APK SHA-256 和源码文件哈希。测试保存并恢复真实单词本，后端边界检查使用独立数据库。每次新构建使用新的目录，旧验证不自动适用于新 APK。
