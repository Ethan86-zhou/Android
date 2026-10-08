# Android APP 项目集合

本目录使用一个 Git 仓库管理多个独立的 Android APP。每个 APP 放在 `apps/<项目名>/` 中，保留自己的源码、Gradle Wrapper、项目说明和交付文件。项目可以由不同 AI 模型开发，也可以人工开发。

GitHub 仓库：[Ethan86-zhou/Android](https://github.com/Ethan86-zhou/Android)，采用私有仓库。

## 项目索引

| 项目 | 目录 | 用途 | 安装包 |
| --- | --- | --- | --- |
| ForceSense 力感知 | [apps/forcesense](apps/forcesense/README.md) | 读取真实光感数据，模拟侧键按压力及 1.8 × 4 mm 力传感器形变 | [ForceSense-1.0.0-debug.apk](apps/forcesense/outputs/ForceSense-1.0.0-debug.apk) |

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

新克隆仓库后，先在根目录启用提交检查：

```powershell
.\scripts\setup-git-hooks.ps1
```

检查会拒绝缺少前缀或缺少说明的标题。它只校验格式，实际模型名称由开发者或 AI 根据运行配置确认。通过 GitHub 网页、API 或其他绕过本地 hook 的方式提交时，也必须遵守相同规则。

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

仓库跟踪源码、构建配置、Gradle Wrapper、说明文档和有意保留的交付文件。Gradle 缓存、构建临时目录、IDE 配置、`local.properties`、本机编译的检查类和签名密钥由 `.gitignore` 排除；它们仍可在本机使用。设备验证记录可能包含设备序列号，后续若改成公开仓库，应先审查这些记录。
