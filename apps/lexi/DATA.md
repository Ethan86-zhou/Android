# 离线词库来源与复现

应用内置 [ECDICT](https://github.com/skywind3000/ECDICT) 仓库基础版的完整 `ecdict.csv`，固定提交为 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`。在原英汉数据上建立本机中文反查索引，同时支持英文查中文和中文查英文。采用上游约 76 万条的基础版。

上游仓库的 [LICENSE](https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE) 为 MIT，版权行是 `Copyright (c) 2025 Linwei`。许可证原文未经修改保留在 APK 的 `licenses/ECDICT-MIT.txt`，项目网址及归属保留在 `licenses/NOTICE.txt`，应用设置页也可查看。

## 数据范围

| 项目 | 数量 |
| --- | ---: |
| 全部词条 | 770,611 |
| `translation` 字段非空 | 768,739 |
| 中文反查索引词条（释义含汉字） | 767,533 |
| 汉字倒排项（每条释义内去重） | 5,283,372 |
| 完整词义与词条映射（每条释义内去重） | 1,677,907 |
| 有音标 | 218,065 |
| 额外词形检索映射 | 56,945 |
| SQLite 解压后大小 | 164,454,400 字节 |
| APK 内压缩词库大小 | 67,747,073 字节 |

上游部分词条缺少中文释义或音标，应用显示已有字段，不编造补充。完整保留原始单词、音标、中英文释义、考试标签和词形变化；未使用的上游字段不导入应用。词库不包含发音音频或统一的例句库。

每个 ID 为固定源 CSV 中从 1 开始的逻辑记录序号。未来更换词库版本时不能把这个 ID 直接解释为新词库的相同单词；生词本另存完整词条快照。源 CSV 的 SHA-256 为 `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`。

## 构建与检查

`tools/build_dictionary.py` 仅依赖 Python 3 标准库。首次运行下载固定提交的源文件到被 Git 忽略的 `build/dictionary-source/`；以后核验 SHA-256 后复用原文件，不修改原始 CSV。加 `--offline` 时只使用已缓存且哈希匹配的源文件，缺失时中止。本次升级使用完整缓存离线生成。运行位置为本 APP 根目录：

```text
python tools/build_dictionary.py --offline --reproducibility --report outputs/<新的验证目录>/dictionary-validation.json
python tools/build_dictionary.py --verify --report outputs/<新的验证目录>/dictionary-asset-validation.json
```

首次命令生成 `app/src/main/assets/dictionary.pack`、`dictionary-source.json` 和许可证副本，完整导入所有 CSV 记录。`.pack` 内仍为 gzip 压缩数据，采用该扩展名并在 Gradle 中设置 `noCompress 'pack'`，避免 Android 打包工具自动解压或改名 `.gz` 资源。`--reproducibility` 另外构建第二份 SQLite 和 gzip，比较字节级 SHA-256。第二条命令不下载文件，检查已交付压缩包、解压数据库、许可证哈希、数据库各实体表、条数、关联记录和双向查询。中文索引核对每个汉字 token 的完整 docid 列表、词频统计及全部释义记录覆盖。完整词义索引另用独立的逐分隔符替换与 UTF-16 编码长度解析器，从原释义重新推导并比较每一个 `(meaning, entry_id)`。另测空查询、无结果、百分号、下划线、引号、FTS 操作符和补充平面汉字。验证输出文件如果已存在会拒绝覆盖，应选择新的目录。

宿主 SQLite 3.53.1 的整库 `PRAGMA integrity_check` 对 contentless FTS4 会报告无法校验原内容，这是 [SQLite 官方确认的缺陷](https://sqlite.org/bugs/info/442c6e4a5af8adc63c91e5eebb980e7405d8f859538f4897231e7b5617e13be8?t=c)。验证报告保留整库检查的原始结果，分别对 `entries`、`aliases`、`metadata`、`chinese_meanings` 及全部 FTS 实体表执行完整性检查，再独立将每个倒排项与原始释义计算所得的 docid 列表逐项比较。不会将该整库诊断写成 `ok`；任何其他整库诊断或实体表、倒排项不一致都会中止生成。

`dictionary-source.json` 保存源文件网址及哈希、转换规则、精确数量、SQLite 版本和数据库/压缩包哈希。当前 `source_id` 为 `ecdict-base-bc015ed2e24a-schema2`，SQLite `user_version` 与清单 `schema_version` 均为 2。数据库 SHA-256 为 `3d4869e37d4d8f0ef6b402d86d3176ee654f5e8fe67cb1df8975c3d5a3a2dedc`，gzip SHA-256 为 `3d1355ecf4a140e18cfdd2abb5b7cf56e2e341a1b2d84a2cca1a76e124aa83ab`。字节复现以记录的 SQLite 版本及当前 Python/zlib 实现为基准；其他版本仍可用 `--verify` 验证已打包内容。

索引审计自身的回归命令为 `python -m unittest discover -s tools -p test_dictionary_index.py -v`，通过故意交换 docid（总计数仍正确）、删除覆盖记录、重复 token、词义归属互换及额外孤立映射等样本确认审计能够拒绝错误索引，也核对分隔符、空格和 UTF-16 长度边界。

## 查询与本机安装

词库只读，通过索引查询精确单词、词形及前缀。检索忽略大小写与首尾空白，兼容弯引号及长短横线；精确结果在前，其后是词形还原和按语料词频排序的前缀结果。词频排名取上游正数 `frq`、`bnc` 的较小值，缺失值排在后面。词形原文保留，另外将 `p/d/i/3/r/t/s/b/z/f` 对应变体建立索引；例如 `went` 可关联 `go`、`children` 可关联 `child`。

schema 2 新增 contentless FTS4 表 `chinese_search(terms, content='', tokenize=simple)`；原 `entries`、ID 和 `aliases` 内容完整保留。每条含汉字的中文释义以原词条 ID 作为 `docid`，将去重并排序的汉字码点编码成 ASCII token，例如“苹果”为 `h679c h82f9`。汉字范围固定为 U+3400–U+4DBF、U+4E00–U+9FFF、U+F900–U+FAFF、U+20000–U+323AF，包含补充平面汉字。

完整词义索引为 `chinese_meanings(meaning TEXT NOT NULL, entry_id INTEGER NOT NULL, PRIMARY KEY(meaning, entry_id)) WITHOUT ROWID`。编码格式在 `metadata.chinese_meaning_format` 固定为 `lower-no-ascii-space-segments-v1`：原释义转小写、删除 ASCII 空格与 CR，再以 LF、英文/中文逗号、英文/中文分号、英文句点和右方括号分段；保留含上述汉字且长度不超过 256 个 UTF-16 单元的部分，同一词条内去重。`chinese_meaning_max_utf16_length` 与 `chinese_meaning_count` 同时记录限制和映射总数。

中文查询先通过复合主键等值查完整词义，结果满足显示条数时直接返回；不足时取全部查询汉字 token 的 AND 交集，追加字面子串结果并按词条 ID 去重。两个阶段均用参数化 `instr(lower(translation), normalizedQuery)` 验证原释义，保证空格和特殊符号按字面匹配。完整词义在前，同一阶段按词频、释义长度等排序。查询“苹果”可返回 `apple`，“计算机”可返回 `computer`，点击后继续使用原词条及单词本功能。中文反查范围取决于上游已有释义文字，未提供任意句子翻译或简繁转换。

首次启动在后台从 APK 解压词库，校验完整数据库的大小和 SHA-256 后原子改名。不同界面实例共享安装锁，避免屏幕旋转造成安装竞争。后续启动核对本机数据库的版本与来源，无需联网或重新导入。词库数据库与生词本数据库分开，更新或修复词库不会删除生词本。

本地管线验证不代替 Android 实机验证。APP 的新构建仍需分别记录对应 APK 哈希、手机环境、查询和导出结果。
