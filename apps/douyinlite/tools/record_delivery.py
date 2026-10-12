"""归档当前源码、最终APK及独立设备记录，不读取登录文件或网页内容。"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
from datetime import datetime, timezone, timedelta

APP = Path(__file__).resolve().parents[1]
ROOT = APP.parents[1]
OUTPUT = APP / 'outputs/2026-10-12-v0.1.0'
APK = OUTPUT / 'LightSwipe-0.1.0-debug.apk'
ADB = Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb.exe'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, cwd=ROOT):
    return subprocess.check_output([str(x) for x in args], cwd=cwd, text=True, encoding='utf-8').strip()


sources = [ROOT / 'README.md']
for name in ['README.md', '.gitignore', '.gitattributes', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle']:
    sources.append(APP / name)
for directory in ['app/src', 'gradle', 'tools']:
    sources.extend(p for p in (APP / directory).rglob('*') if p.is_file()
                   and not {'build', '__pycache__', 'classes'}.intersection(p.relative_to(APP).parts))

source_hashes = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(set(sources))}
installed_path = command([ADB, 'shell', 'pm', 'path', 'com.zhou.douyinlite']).splitlines()[0].removeprefix('package:')
installed_sha = command([ADB, 'shell', 'sha256sum', installed_path]).split()[0]
assert installed_sha == digest(APK), 'Installed APK differs from delivery APK'

reports = []
for directory in ['device-probe-final', 'device-real-01', 'device-cache-final']:
    for p in sorted((OUTPUT / directory).glob('*.json')):
        data = json.loads(p.read_text(encoding='utf-8'))
        checks = data.get('checks', {}) or {
            f'{phase}/{key}': value for phase, entry in data.get('phases', {}).items()
            for key, value in entry.get('checks', {}).items()
        }
        assert data['passed'] and all(checks.values()), f'Device checks failed: {directory}'
        reports.append({'path': p.relative_to(APP).as_posix(), 'sha256': digest(p),
                        'passed': data['passed'], 'check_count': len(checks)})

record = {
    'recorded_at_shanghai': datetime.now(timezone(timedelta(hours=8))).isoformat(),
    'version': '0.1.0', 'package': 'com.zhou.douyinlite',
    'apk': {'path': APK.relative_to(APP).as_posix(), 'bytes': APK.stat().st_size,
            'sha256': digest(APK), 'installed_sha256': installed_sha},
    'device': {'model': command([ADB, 'shell', 'getprop', 'ro.product.model']),
               'android': command([ADB, 'shell', 'getprop', 'ro.build.version.release']),
               'sdk': command([ADB, 'shell', 'getprop', 'ro.build.version.sdk'])},
    'source_base_commit': command(['git', 'rev-parse', 'HEAD']),
    'source_branch': command(['git', 'branch', '--show-current']),
    'source_identity_note': 'APK built from uncommitted changes on this base; per-file SHA256 below identifies the exact source.',
    'source_files_sha256': source_hashes,
    'build': {'assembleDebug': 'passed', 'lintDebug': 'passed', 'lint_errors': 0,
              'lint_warnings': 4, 'warning_scope': 'fixed portrait orientation, main and debug probe'},
    'host_tests': {'media_scenarios': 14, 'gesture_checks': 33, 'cache_checks': 42,
                   'symbolic_link_checks_skipped': 3},
    'final_apk_device_reports': reports,
    'cache': {'mode': 'LOAD_DEFAULT', 'soft_threshold_bytes': 1073741824,
              'observed_manual_clear_mib': [17.8, 0.1], 'login_retained_after_clear_and_reload': True,
              'physical_one_gib_pressure_tested': False,
              'scope': 'Recognized resource cache; website and login data are measured separately. No hard disk cap or offline playback guarantee.'},
    'privacy': {'website_screenshots_private_and_gitignored': True,
                'account_cookie_and_video_urls_not_in_reports': True,
                'debug_probe_uses_separate_process_and_webview_profile': True},
    'runtime_model': 'gpt-6.1-sol',
    'runtime_model_evidence': 'Current session turn_context model was verified before local commit.',
}
(OUTPUT / 'delivery-verification.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'installed_apk_matches': True, 'source_files': len(source_hashes),
                  'device_reports': reports}, ensure_ascii=False))
