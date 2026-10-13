"""归档明确指定的本次构建与验收，不读取登录文件或网页内容。"""
import argparse
import ast
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET


APP = Path(__file__).resolve().parents[1]
ROOT = APP.parents[1]
PACKAGE = 'com.zhou.douyinlite'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, cwd=ROOT):
    return subprocess.check_output([str(x) for x in args], cwd=cwd,
                                   text=True, encoding='utf-8').strip()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def reference(path):
    """仓库内用相对路径；显式指定的外部证据保留绝对路径。"""
    path = path.resolve(strict=True)
    try:
        name = path.relative_to(APP).as_posix()
    except ValueError:
        name = str(path)
    return {'path': name, 'sha256': digest(path)}


def report_checks(data):
    """同时检查顶层和分阶段 checks，拒绝字符串或数字冒充 true。"""
    checks = {}

    def visit(value, prefix=''):
        if isinstance(value, dict):
            if 'passed' in value:
                require(value['passed'] is True, f'Report failed at {prefix or "root"}')
            for key, item in value.items():
                name = f'{prefix}/{key}' if prefix else key
                if key == 'checks':
                    require(isinstance(item, dict), f'{name} must be an object')
                    for check, result in item.items():
                        require(result is True, f'Check is not true: {name}/{check}')
                        checks[f'{name}/{check}'] = result
                else:
                    visit(item, name)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, f'{prefix}/{index}')

    visit(data)
    return checks


def read_report(path, apk_sha, require_checks=True):
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    require(isinstance(data, dict), f'Report must be an object: {path}')
    require(data.get('passed') is True, f'Report did not pass: {path}')
    checks = report_checks(data)
    if require_checks:
        require(bool(checks), f'Report has no explicit checks: {path}')
    # 新报告可以直接声明 APK；没有该字段的报告仍由调用者显式选择。
    declared_sha = data.get('apk_sha256') or data.get('apkSha256')
    if declared_sha is not None:
        require(str(declared_sha).lower() == apk_sha, f'Report APK differs: {path}')
    summary = reference(path)
    summary.update(passed=True, check_count=len(checks))
    return data, summary


def build_result(log_path, lint_path):
    raw = log_path.read_bytes()
    encoding = 'utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig'
    log = raw.decode(encoding).replace('\r\n', '\n').replace('\r', '\n')
    log = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', log)
    require('BUILD SUCCESSFUL' in log, 'Final build log does not show BUILD SUCCESSFUL')
    require('BUILD FAILED' not in log and 'FAILURE: Build failed' not in log,
            'Final build log contains a failed build')
    result = {'log': reference(log_path), 'lint_report': reference(lint_path)}
    for task in ('assembleDebug', 'lintDebug'):
        entries = re.findall(r'^> Task :(?:app:)?' + task + r'(?:[ \t]+([^\r\n]*))?$',
                             log, flags=re.MULTILINE)
        require(bool(entries), f'Final build log has no {task} task')
        state = entries[-1].strip() or 'EXECUTED'
        require(state not in ('FAILED', 'SKIPPED', 'NO-SOURCE'), f'{task} did not complete: {state}')
        result[task] = 'passed'
        result[f'{task}_gradle_state'] = state
    document = ET.parse(lint_path).getroot()
    require(document.tag == 'issues', 'Unrecognized lint XML root')
    severities = {}
    for issue in document.findall('issue'):
        severity = issue.get('severity', 'Unknown')
        severities[severity] = severities.get(severity, 0) + 1
    errors = sum(count for severity, count in severities.items()
                 if severity.lower() in ('fatal', 'error'))
    require(errors == 0, f'Lint has {errors} errors')
    result.update(lint_errors=errors,
                  lint_warnings=sum(count for severity, count in severities.items()
                                    if severity.lower() == 'warning'),
                  lint_issues_by_severity=severities)
    return result


def integer_expression(text):
    """仅允许 Java 整数字面量的加减乘，不执行源码表达式。"""
    text = re.sub(r'(?<=\d)[lL]\b', '', text).replace('_', '')

    def number(node):
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.BinOp):
            left, right = number(node.left), number(node.right)
            if isinstance(node.op, ast.Mult):
                return left * right
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
        raise ValueError('Unsupported cache threshold expression')

    return number(ast.parse(text, mode='eval').body)


def cache_result(report_path, apk_sha):
    source = APP / 'app/src/main/java/com/zhou/douyinlite/CacheController.java'
    text = source.read_text(encoding='utf-8')
    declaration = r'\bstatic\s+final\s+long\s+SOFT_LIMIT_BYTES\s*=\s*([^;]+);'
    match = re.search(declaration, text)
    require(match is not None, 'Cache threshold declaration missing')
    expression = match.group(1).strip()
    public_alias_source = None
    # 仅展开本项目明确约定的单层别名；不解析任意成员引用或执行 Java/Python 表达式。
    if expression == 'CachePolicy.SOFT_LIMIT_BYTES':
        public_alias_source = source
        source = source.with_name('CachePolicy.java')
        policy_text = source.read_text(encoding='utf-8')
        policy_match = re.search(declaration, policy_text)
        require(policy_match is not None, 'Cache policy threshold declaration missing')
        expression = policy_match.group(1).strip()
    threshold = integer_expression(expression)
    require(threshold > 0, 'Invalid cache threshold')
    result = {
        'threshold_source': reference(source),
        'soft_threshold_bytes': threshold,
        'one_gib_soft_threshold_unchanged': threshold == 1024 ** 3,
        'physical_one_gib_pressure_tested': False,
        'scope': 'Recognized resource cache; website and login data are measured separately. '
                 'No hard disk cap or offline playback guarantee.',
    }
    if public_alias_source is not None:
        result['public_alias_source'] = reference(public_alias_source)
    if 'setCacheMode(WebSettings.LOAD_DEFAULT)' in text:
        result['mode'] = 'LOAD_DEFAULT'
    if report_path is not None:
        data, summary = read_report(report_path, apk_sha)
        result['device_report'] = summary
        # 只收录显式指定报告中的实际数值，不沿用任何历史缓存观察。
        observations = {key: data[key] for key in (
            'cacheMiBBefore', 'cacheMiBAfter', 'cacheBytesBefore', 'cacheBytesAfter',
            'siteBytesBefore', 'siteBytesAfter', 'limitBytes')
            if key in data and type(data[key]) in (int, float)}
        if observations:
            result['observations'] = observations
        checks = data.get('checks', {})
        if checks.get('loginPresentAfterClear') is True and checks.get('loginPresentAfterReload') is True:
            result['login_retained_after_clear_and_reload'] = True
    return result


def source_hashes():
    sources = [ROOT / 'README.md']
    for name in ('README.md', '.gitignore', '.gitattributes', 'build.gradle', 'settings.gradle',
                 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle'):
        sources.append(APP / name)
    for directory in ('app/src', 'gradle', 'tools'):
        sources.extend(path for path in (APP / directory).rglob('*') if path.is_file()
                       and not {'build', '__pycache__', 'classes', 'node_modules'}.intersection(
                           path.relative_to(APP).parts))
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(set(sources))}


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', required=True, type=Path, help='本次交付目录，按当前工作目录解析')
    parser.add_argument('--device-report', required=True, action='append', type=Path,
                        help='本次通过的设备 JSON，可重复指定；不自动扫描旧记录')
    parser.add_argument('--build-log', required=True, type=Path, help='最终 assembleDebug lintDebug 完整日志')
    parser.add_argument('--lint-report', required=True, type=Path, help='本次 lint-results-debug.xml')
    parser.add_argument('--host-test-report', type=Path, help='本次主机检查 JSON，passed=true，可含 counts')
    parser.add_argument('--cache-report', type=Path, help='本次缓存实机报告；未指定则不写实际缓存量值')
    parser.add_argument('--apk', type=Path, help='默认使用交付目录中的 LightSwipe-<version>-debug.apk')
    parser.add_argument('--serial', help='明确选择已安装本次 APK 的设备')
    parser.add_argument('--adb', type=Path, help='覆盖本机 adb 路径')
    parser.add_argument('--runtime-model', help='经当前运行配置确认的实际模型名，不推断')
    return parser.parse_args()


def main():
    args = parse_args()
    output = args.output.resolve(strict=True)
    destination = output / 'delivery-verification.json'
    require(not destination.exists(), f'Refusing to overwrite existing record: {destination}')
    apk = (args.apk or output / f'LightSwipe-{args.version}-debug.apk').resolve(strict=True)
    gradle = (APP / 'app/build.gradle').read_text(encoding='utf-8')
    version = re.search(r'\bversionName\s+[\'\"]([^\'\"]+)', gradle)
    require(version is not None and version.group(1) == args.version,
            'Requested version differs from current app/build.gradle')
    apk_sha = digest(apk)
    reports = []
    for path in dict.fromkeys(path.resolve(strict=True) for path in args.device_report):
        _, summary = read_report(path, apk_sha)
        reports.append(summary)
    build = build_result(args.build_log, args.lint_report)
    cache = cache_result(args.cache_report, apk_sha)
    host = None
    if args.host_test_report is not None:
        data, summary = read_report(args.host_test_report, apk_sha, require_checks=False)
        host = {'report': summary}
        if 'counts' in data:
            require(isinstance(data['counts'], dict), 'Host counts must be an object')
            require(all(type(value) is int and value >= 0 for value in data['counts'].values()),
                    'Host counts must be nonnegative integers')
            host['counts'] = data['counts']
        if 'skipped' in data:
            host['skipped'] = data['skipped']
    adb = args.adb
    if adb is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        adb = Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb') if sdk else shutil.which('adb')
    require(adb is not None, 'Set ANDROID_HOME or pass --adb')
    adb_prefix = [adb] + (['-s', args.serial] if args.serial else [])
    installed_paths = [line.removeprefix('package:') for line in
                       command(adb_prefix + ['shell', 'pm', 'path', PACKAGE]).splitlines()
                       if line.startswith('package:')]
    require(bool(installed_paths), f'Package is not installed: {PACKAGE}')
    installed_path = next((path for path in installed_paths if path.endswith('/base.apk')),
                          installed_paths[0])
    installed_sha = command(adb_prefix + ['shell', 'sha256sum', installed_path]).split()[0].lower()
    require(installed_sha == apk_sha, 'Installed APK differs from delivery APK')
    record = {
        'recorded_at_shanghai': datetime.now(timezone(timedelta(hours=8))).isoformat(),
        'version': args.version,
        'package': PACKAGE,
        'apk': {**reference(apk), 'bytes': apk.stat().st_size, 'installed_sha256': installed_sha},
        'device': {'serial': command(adb_prefix + ['get-serialno']),
                   'model': command(adb_prefix + ['shell', 'getprop', 'ro.product.model']),
                   'android': command(adb_prefix + ['shell', 'getprop', 'ro.build.version.release']),
                   'sdk': command(adb_prefix + ['shell', 'getprop', 'ro.build.version.sdk'])},
        'source_base_commit': command(['git', 'rev-parse', 'HEAD']),
        'source_branch': command(['git', 'branch', '--show-current']),
        'source_worktree_status': command(['git', 'status', '--porcelain', '--', 'README.md', 'apps/douyinlite']),
        'source_identity_note': 'Base commit plus per-file SHA256 identifies the recorded source snapshot; '
                                'uncommitted changes are not represented by the base commit alone.',
        'source_files_sha256': source_hashes(),
        'build': build,
        'final_apk_device_reports': reports,
        'cache': cache,
    }
    if host is not None:
        record['host_tests'] = host
    if args.runtime_model:
        record['runtime_model'] = args.runtime_model
        record['runtime_model_evidence'] = 'Explicitly provided by the caller after checking current runtime configuration.'
    # x 模式防止并行调用在检查后覆盖已有原始交付记录。
    with destination.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'record': str(destination), 'installed_apk_matches': True,
                      'source_files': len(record['source_files_sha256']),
                      'device_reports': reports}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        raise SystemExit(f'Delivery record refused: {error}') from error
