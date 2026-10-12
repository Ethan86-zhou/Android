# Android APP 项目集合

本目录使用一个 Git 仓库管理多个独立的 Android APP。每个 APP 放在 `apps/<项目名>/` 中，保留自己的源码、Gradle Wrapper、项目说明和交付文件。项目可以由不同 AI 模型开发，也可以人工开发，并通过 GitHub 在多台电脑之间同步。

GitHub 仓库：[Ethan86-zhou/Android](https://github.com/Ethan86-zhou/Android)，按仓库所有者要求公开。个人词本、凭据、签名密钥和本机配置不纳入仓库。

## 项目索引

| 项目 | 目录 | 用途 | 安装包 |
| --- | --- | --- | --- |
| ForceSense 力感知 | [apps/forcesense](apps/forcesense/README.md) | 读取真实光感数据，模拟侧键按压力及 1.8 × 4 mm 力传感器形变 | [ForceSense-1.0.0-debug.apk](apps/forcesense/outputs/ForceSense-1.0.0-debug.apk) |
| 拾词 Lexi | [apps/lexi](apps/lexi/README.md) | 内置 ECDICT 离线英汉词库，快捷查词、单词本、笔记与 CSV / Markdown / JSON 导出恢复 | [Lexi-1.0.2-debug.apk](apps/lexi/outputs/2026-10-11-v1.0.2/Lexi-1.0.2-debug.apk) |
| 果刃 FruitSlice | [apps/fruitslice](apps/fruitslice/README.md) | 手机与平板横屏切水果，抬手出刀，彩虹果实随机换模式，本机最高分 10 局；Android 14 起 | [FruitSlice-1.2.2-debug.apk](apps/fruitslice/outputs/2026-10-11-v1.2.2-end-sound/FruitSlice-1.2.2-debug.apk) |
| 轻听 LightListen | [apps/musiccache](apps/musiccache/README.md) | 固定竖屏；QQ 音乐网页登录、原生搜索与歌单、后台播放和自动缓存 | [LightListen-0.3.1-debug.apk](apps/musiccache/outputs/2026-10-11-v0.3.1-portrait/LightListen-0.3.1-debug.apk) |
| 轻刷 LightSwipe | [apps/douyinlite](apps/douyinlite/README.md) | 抖音官网精选单列浏览 / 推荐、手机播放与进度拖动、上滑切视频、边缘长按加速与下滑锁定、1 GiB 缓存软阈值 | [LightSwipe-0.3.1-debug.apk](apps/douyinlite/outputs/2026-10-13-v0.3.1-single-column/LightSwipe-0.3.1-debug.apk) |

## 目录约定

```text
Android/
├── README.md                 # APP 索引、开发和提交约定
├── AGENTS.md                 # AI 开发工具共用的工作规则
├── .gitignore
├── .gitattributes
├── .githooks/commit-msg      # 检查提交标题格式
├── scripts/setup-git-hooks.ps1
└── apps/
    └── forcesense/
        ├── README.md
        ├── app/src/
        ├── gradle/wrapper/
        ├── gradlew / gradlew.bat
        ├── build.gradle / settings.gradle
        ├── tools/
        └── outputs/          # 交付 APK 和验证记录
```

新增 APP 时使用简短的小写目录名，例如 `apps/light-meter/`，并更新项目索引。各项目独立构建；Android Studio 应打开具体 APP 目录。所有项目共用根目录的 `.git`，项目内不创建嵌套 Git 仓库。

仓库可克隆到任意本地路径，目录名也可自定；`C:\Users\zhou\Documents\Android` 只是首次开发电脑的位置。文档中的项目路径均相对于实际克隆目录，AI 应以当前仓库为准。

## 提交必须以模型名开头

提交标题统一采用：

```text
<实际模型名>: <项目或范围> - <变更说明>
```

例如：

```text
gpt-6.1-sol: forcesense - 增加光感驱动的形变动画
claude-sonnet-4.5: light-meter - 增加光照历史曲线
manual: forcesense - 调整按钮文案
```

AI 提交填写当次实际使用的模型名，不使用 `AI`、`Codex` 等工具名代替模型，也不猜测版本。完全人工开发使用 `manual:`。多个模型参与时，以主要贡献模型名开头，并在提交正文列出其他模型及贡献。模型名前缀记录在提交标题中，Git 作者仍使用开发者自己的身份。

每个新克隆都要按下方流程启用提交检查。检查会拒绝缺少前缀或缺少说明的标题。它只校验格式，实际模型名称由开发者或 AI 根据运行配置确认。通过 GitHub 网页、API 提交或合并 PR 时，也必须让最终的提交标题遵守相同规则。

## 新电脑首次设置

1. 安装 Git、Android Studio 和项目需要的 JDK / Android SDK。公开仓库可以直接克隆；推送时登录有写入权限的 GitHub 账号。通过 Git Credential Manager、GitHub Desktop 或 SSH 管理登录凭据，不在仓库 URL 中放令牌。
2. 进入准备保存项目的父目录，然后克隆：

```powershell
git clone https://github.com/Ethan86-zhou/Android.git Android
Set-Location .\Android
```

3. 在仓库根目录配置 Git 身份和提交检查。以下使用仓库所属账号的 GitHub 隐私邮箱；换账号时应修改姓名和邮箱：

```powershell
git config --local user.name 'Ethan86-zhou'
git config --local user.email '195117817+Ethan86-zhou@users.noreply.github.com'
.\scripts\setup-git-hooks.ps1
git config --get core.hooksPath
```

最后一条应输出 `.githooks`。macOS / Linux 可直接执行 `git config --local core.hooksPath .githooks`。`.githooks/` 文件会随仓库同步，但启用它的 `.git/config` 配置及 Git 登录状态不会同步，**每台电脑、每个克隆都要单独设置**。Git 作者身份与提交标题中的模型名是两项独立信息；hook 路径配置见 [Git 官方文档](https://git-scm.com/docs/git-config#Documentation/git-config.txt-corehooksPath)。

4. 用 Android Studio 打开需要开发的 APP 目录，例如 `apps/forcesense/`，并配置本机的 Gradle JDK 和 SDK 路径。ForceSense 使用 JDK 17 或兼容 JBR、Android SDK Platform 35 和 Platform Tools，并按 Gradle 同步或构建提示安装所需 Build Tools；具体配置见 [项目说明](apps/forcesense/README.md)。Gradle 和插件版本以项目已固定的配置为准。
5. 由 Android Studio 配置本机 SDK 路径，或在对应 APP 目录创建自己的 `local.properties`。该文件已被忽略，不会从 GitHub 下载，也不应复制另一台电脑的 SDK 路径。`sdk.dir` 含义见 [Android 构建配置文档](https://developer.android.com/build#properties-files)。
6. 使用项目自己的 Wrapper 构建：Windows 运行 `gradlew.bat`，macOS / Linux 运行 `./gradlew`。首次构建需要联网下载对应 Gradle 和依赖，无需另外安装全局 Gradle。

## 换电脑前后的同步

每次开始开发，在仓库根目录检查当前分支与工作区；工作区干净时拉取该分支的最新提交：

```powershell
git status --short --branch
git pull --ff-only
```

若有未提交修改，先提交到自己的工作分支，或用 `git stash push -u` 暂存，拉取后再 `git stash pop` 并处理可能的冲突；stash 只存在当前电脑，不能用于向另一台电脑交接。`--ff-only` 在双方历史分叉时会停止，不自动生成合并提交，见 [Git 拉取文档](https://git-scm.com/docs/git-pull)。

结束开发或准备换电脑时，先检查改动、完成对应验证，再提交并推送。下面的模型名应替换为当次真实模型名；完全人工开发改用 `manual:`：

```powershell
git diff
git add apps/forcesense README.md AGENTS.md
git commit -m 'gpt-6.1-sol: forcesense - 描述本次变更'
git push
git status --short --branch
```

确认推送成功、当前分支没有待推送提交后，再到另一台电脑拉取。未提交文件、未推送提交、本机 stash，以及被忽略的 SDK 配置、缓存和密钥不会自动同步。临时工作也可以提交到任务分支，以实际模型名前缀写清进度并推送。

若另一台电脑首次接手已经推送的任务分支：

```powershell
git fetch origin
git switch --track origin/feature/forcesense-ui
```

示例分支名应替换为实际任务分支；若本地已有该分支，使用 `git switch feature/forcesense-ui`，随后 `git pull --ff-only`。

## 两台电脑同时开发

并行任务各用一个分支，避免两台电脑同时直接向 `main` 推送。例如，在干净且已更新的 `main` 上创建任务分支：

```powershell
git switch main
git pull --ff-only
git switch -c feature/forcesense-ui-pc2
# 完成改动、检查并按模型前缀规则提交后：
git push -u origin feature/forcesense-ui-pc2
```

通过 GitHub PR 合并到 `main`，合并或 squash 的最终提交标题也要包含实际模型名前缀。合并前更新远端状态；有冲突时保留双方需要的改动并重新验证。若推送因历史分叉被拒绝，先 `git fetch origin` 核对提交，再显式合并或只对自己的未发布提交 rebase；不要用强制推送覆盖另一台电脑的工作。显式合并提交也应设置符合规则的标题。

## 跨电脑构建与签名

每台电脑的 SDK、JDK、USB 调试授权和签名配置分别设置。不同电脑默认生成的 debug keystore 可能不同，因此同包名 APK 的覆盖安装可能因签名不一致失败；Android 更新安装会核对签名，见 [Android 签名文档](https://developer.android.com/studio/publish/app-signing#considerations)。

需要保留手机 APP 数据时，用相同的开发签名密钥构建，可在仓库外安全备份和传递 debug keystore。若选择卸载旧 APP 再安装，应先确认可以清除它的数据；ForceSense 的零点和视图设置也会清除。AI 不应为解决签名问题自动卸载 APP。正式发布的签名密钥、密码及上传密钥另行保管，不提交到 Git。

新电脑生成的 APK 应与其源码提交对应，交付时记录新的哈希和验证结果。仓库中的旧 APK 与旧实机记录是历史交付证据，不能当作新构建的实机验证结果。新增证据使用独立的日期或版本目录，避免覆盖已有原始记录。

## 构建和提交

ForceSense 构建示例（先按本机环境设置 JDK 和 Android SDK）：

```powershell
Set-Location .\apps\forcesense
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug lintDebug
```

项目运行方法、光感映射、实机记录见 [ForceSense README](apps/forcesense/README.md)。本次迁移保留了已有源码、安装包与实机证据，安装包 SHA-256 为 `83D5DAEDB06B8CC78652E809D8B729784921556B2EE555AD18BF1CF2012CD8E0`。

回到仓库根目录提交：

```powershell
git status
git add apps/forcesense README.md
git commit -m 'gpt-6.1-sol: forcesense - 描述本次变更'
git push
```

仓库跟踪源码、构建配置、Gradle Wrapper、说明文档和有意保留的交付文件。Gradle 缓存、构建临时目录、IDE 配置、`local.properties`、本机编译的检查类和签名密钥由 `.gitignore` 排除；它们仍可在本机使用。仓库已公开，新增设备验证记录在提交前检查账号信息、登录截图及凭据；历史原始记录保持不变。

## 仓库自动检查

在仓库根目录运行 `python scripts/check-repository.py`，检查 APP 独立构建文件、项目索引、构建脚本执行权限、提交标题 hook、误跟踪的本机配置和嵌套 Git。脚本只读，不构建或安装 APP；应用行为仍按各项目 README 验证。加 `--local` 可额外核对当前克隆的 Git 身份与 hook 配置。

GitHub 的 Repository checks 工作流在推送和 PR 时执行相同检查，并核对新增提交的标题格式。通过 PR 合并到 `main`，保留功能分支及原始交付证据；其他工作目录正在使用的分支不自动删除。
