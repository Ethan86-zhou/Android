$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$actualGitRoot = git -C $repositoryRoot rev-parse --show-toplevel
if ($LASTEXITCODE -ne 0) { throw '请先将 Android 根目录初始化或克隆为 Git 仓库。' }
if ([IO.Path]::GetFullPath($actualGitRoot) -ne [IO.Path]::GetFullPath($repositoryRoot)) {
    throw 'Android 必须是 Git 仓库根目录，不能在父仓库内配置此 hook。'
}
git -C $repositoryRoot config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { throw '配置提交检查失败。' }
Write-Output '已启用模型名前缀检查：.githooks/commit-msg'
