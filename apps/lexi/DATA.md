# 离线词库来源与复现

应用内置 [ECDICT](https://github.com/skywind3000/ECDICT) 仓库基础版的完整 `ecdict.csv`，固定提交为 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`。这是上游约 76 万条的基础版，不是另行发布的数百万条增强版。

上游仓库的 [LICENSE](https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE) 为 MIT，版权行是 `Copyright (c) 2025 Linwei`。许可证原文未经修改保留在 APK 的 `licenses/ECDICT-MIT.txt`，项目网址及归属保留在 `licenses/NOTICE.txt`，应用设置页也可查看。

## 数据范围

| 项目 | 数量 |
| --- | ---: |
| 全部词条 | 770,611 |
| 有中文释义 | 768,739 |
| 有音标 | 218,065 |
| 额外词形检索映射 | 56,945 |
| SQLite 解压后大小 | 102,084,608 字节 |
| APK 内压缩词库大小 | 41,101,356 字节 |

上游部分词条缺少中文释义或音标，应用显示已有字段，不编造补充。完整保留原始单词、音标、中英文释义、考试标签和词形变化；未使用的上游字段不导入应用。词库不包含发音音频或统一的例句库。

每个 ID 为固定源 CSV 中从 1 开始的逻辑记录序号。未来更换词库版本时不能把这个 ID 直接解释为新词库的相同单词；生词本另存完整词条快照。源 CSV 的 SHA-256 为 `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`。

## 构建与检查

`tools/build_dictionary.py` 仅依赖 Python 3 标准库。首次运行下载固定提交的源文件到被 Git 忽略的 `build/dictionary-source/`；以后核验 SHA-256 后复用原文件，不修改原始 CSV。运行位置为本 APP 根目录：

```text
python tools/build_dictionary.py --reproducibility --report outputs/<新的验证目录>/dictionary-validation.json
python tools/build_dictionary.py --verify --report outputs/<新的验证目录>/dictionary-asset-validation.json
```

首次命令生成 `app/src/main/assets/dictionary.pack`、`dictionary-source.json` 和许可证副本，完整导入所有 CSV 记录。`.pack` 内仍为 gzip 压缩数据，采用该扩展名并在 Gradle 中设置 `noCompress 'pack'`，避免 Android 打包工具自动解压或改名 `.gz` 资源。`--reproducibility` 另外构建第二份 SQLite 和 gzip，比较字节级 SHA-256。第二条命令不下载文件，检查已交付压缩包、解压数据库、许可证哈希、SQLite 完整性、条数、关联记录和代表性查询。验证输出文件如果已存在会拒绝覆盖，应选择新的目录。

`dictionary-source.json` 保存源文件网址及哈希、转换规则、精确数量、SQLite 版本和数据库/压缩包哈希。当前数据库 SHA-256 为 `d586d6b4724cd0e58c7706159bf4478d79c771c570976e6c3971758a1dbee663`，gzip SHA-256 为 `3c6a0a28339651eb22f26e3a4de6d26fa7a00719b89130b31f35965ef0d6e665`。字节复现以记录的 SQLite 版本及当前 Python/zlib 实现为基准；其他版本仍可用 `--verify` 验证已打包内容。

## 查询与本机安装

词库只读，通过索引查询精确单词、词形及前缀。检索忽略大小写与首尾空白，兼容弯引号及长短横线；精确结果在前，其后是词形还原和按语料词频排序的前缀结果。词频排名取上游正数 `frq`、`bnc` 的较小值，缺失值排在后面。词形原文保留，另外将 `p/d/i/3/r/t/s/b/z/f` 对应变体建立索引；例如 `went` 可关联 `go`、`children` 可关联 `child`。

首次启动在后台从 APK 解压词库，校验完整数据库的大小和 SHA-256 后原子改名。不同界面实例共享安装锁，避免屏幕旋转造成安装竞争。后续启动核对本机数据库的版本与来源，无需联网或重新导入。词库数据库与生词本数据库分开，更新或修复词库不会删除生词本。

本地管线验证不代替 Android 实机验证。APP 的新构建仍需分别记录对应 APK 哈希、手机环境、查询和导出结果。
