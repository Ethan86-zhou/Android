"""只读检查多 APP 仓库结构；仅使用 Python 标准库与 Git。"""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *args], text=True, encoding="utf-8"
    ).strip()


def check(args):
    errors = []
    if Path(git("rev-parse", "--show-toplevel")).resolve() != ROOT:
        errors.append("脚本必须位于仓库根目录的 scripts/ 下")
    tracked = {}
    for entry in git("ls-files", "--stage", "-z").split("\0"):
        if not entry:
            continue
        metadata, name = entry.split("\t", 1)
        mode, _, stage = metadata.split()
        if stage != "0":
            errors.append(f"存在未解决的合并冲突: {name}")
        if mode == "160000":
            errors.append(f"不允许嵌套仓库或子模块: {name}")
        tracked[name] = mode

    # 检查 Git 索引，避免被忽略的本机文件干扰结果。
    for name in tracked:
        path = Path(name)
        if any(part in {"build", ".gradle", ".idea", ".cxx", ".externalNativeBuild", "__pycache__"} for part in path.parts):
            errors.append(f"误跟踪缓存或构建目录: {name}")
        if path.name in {"local.properties", "keystore.properties", "signing.properties"} or path.suffix in {".jks", ".keystore", ".iml"}:
            errors.append(f"误跟踪本机配置或签名密钥: {name}")
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            errors.append(f"误跟踪环境配置: {name}")

    apps = sorted({name.split("/")[1] for name in tracked if name.startswith("apps/")})
    index = git("show", ":README.md")
    for app in apps:
        prefix = f"apps/{app}"
        for suffix in ("README.md", "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
            if f"{prefix}/{suffix}" not in tracked:
                errors.append(f"缺少独立项目文件: {prefix}/{suffix}")
        for stem in ("settings.gradle", "build.gradle"):
            if not any(f"{prefix}/{stem}{ending}" in tracked for ending in ("", ".kts")):
                errors.append(f"缺少 Gradle 配置: {prefix}/{stem}[.kts]")
        if tracked.get(f"{prefix}/gradlew") != "100755":
            errors.append(f"缺少 Linux/macOS 执行权限: {prefix}/gradlew")
        if f"({prefix}/README.md)" not in index:
            errors.append(f"根 README 未索引项目: {prefix}")

    for target in re.findall(r"\]\(([^)]+)\)", index):
        if "://" not in target and not target.startswith("#"):
            relative = target.split("#", 1)[0]
            if relative and relative not in tracked and not any(name.startswith(relative.rstrip("/") + "/") for name in tracked):
                errors.append(f"根 README 链接未纳入 Git: {relative}")

    # 跳过生成物和交付附件，只寻找 APP 内意外创建的 Git 仓库。
    for folder, dirs, files in os.walk(ROOT / "apps", followlinks=False):
        if ".git" in dirs or ".git" in files:
            errors.append(f"APP 内存在嵌套 Git: {Path(folder).relative_to(ROOT)}")
        dirs[:] = [name for name in dirs if name not in {".git", ".gradle", "build", ".idea", "outputs", "__pycache__"}]

    if tracked.get(".githooks/commit-msg") != "100755":
        errors.append("提交检查 hook 必须具有执行权限")
    shell = shutil.which("sh")
    if not shell and os.name == "nt":
        git_executable = shutil.which("git")
        if git_executable:
            candidate = Path(git_executable).resolve().parent.parent / "bin/sh.exe"
            if candidate.exists():
                shell = str(candidate)
    if not shell:
        errors.append("找不到 sh，无法实际验证提交 hook；Windows 请安装 Git for Windows")
    elif ".githooks/commit-msg" in tracked:
        cases = [
            ("gpt-6.1-sol: fruitslice - 修复问题", True),
            ("gpt-6-astra: musiccache - update playback", True),
            ("manual: docs - update index", True),
            ("fix project", False),
            ("gpt-6.1-sol: missing scope and description", False),
            ("gpt-6.1-sol:  - description", False),
            ("gpt-6.1-sol: docs - ", False),
            ("gpt-6.1-sol: docs -", False),
        ]
        if args.commit_range:
            cases.extend((subject, True) for subject in git("log", "--format=%s", args.commit_range).splitlines())
        with tempfile.TemporaryDirectory(prefix="android-git-check-") as directory:
            message = Path(directory) / "message.txt"
            hook = Path(directory) / "commit-msg"
            hook.write_text(git("show", ":.githooks/commit-msg") + "\n", encoding="utf-8", newline="\n")
            for subject, expected in cases:
                message.write_text(subject + "\n", encoding="utf-8")
                result = subprocess.run([shell, str(hook), str(message)], capture_output=True)
                if (result.returncode == 0) != expected:
                    errors.append(f"提交标题检查结果不符: {subject!r}")

    if args.local:
        for field in ("user.name", "user.email"):
            result = subprocess.run(["git", "-C", str(ROOT), "config", "--get", field], capture_output=True, text=True)
            if not result.stdout.strip():
                errors.append(f"当前克隆未配置 {field}")
        result = subprocess.run(["git", "-C", str(ROOT), "config", "--get", "core.hooksPath"], capture_output=True, text=True)
        if result.stdout.strip() != ".githooks":
            errors.append("当前克隆未启用 .githooks；运行 scripts/setup-git-hooks.ps1")

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {len(apps)} APP 项目结构、索引、Git 文件及提交 hook 检查通过")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", help="额外检查当前克隆身份与 hook 配置")
    parser.add_argument("--commit-range", help="用提交 hook 校验此 Git 范围内的所有提交标题")
    try:
        sys.exit(check(parser.parse_args()))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
