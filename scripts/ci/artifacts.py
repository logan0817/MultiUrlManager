#!/usr/bin/env python3
"""构建与验证仅包含公开构建产物的同轮候选。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_path(root, name):
    path = root / name
    if not name or Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink():
        raise ValueError(f'非法产物路径: {name}')
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('产物路径越界')
    return path


def verify(root, sha, run_id, attempt):
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('schemaVersion') != 1:
        raise ValueError('未知 manifest 版本')
    if (manifest['sha'], manifest['runId'], manifest['runAttempt']) != (sha, str(run_id), str(attempt)):
        raise ValueError('候选不属于本轮受测提交/运行')
    files = manifest['files']
    if not files or len(files) != len({f['path'] for f in files}):
        raise ValueError('产物列表为空或存在重复路径')
    for entry in files:
        path = safe_path(root, entry['path'])
        if not path.is_file() or digest(path) != entry['sha256']:
            raise ValueError(f'产物缺失或摘要不匹配: {entry["path"]}')
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    if actual != {f['path'] for f in files} | {'manifest.json'}:
        raise ValueError('候选包含未声明文件')
    names = {s['name'] for s in manifest['suites']}
    if names != {'app', 'library', 'consumer-minified', 'upgrade-debug', 'upgrade-release'} or len(manifest['suites']) != 5:
        raise ValueError('必需的仪器测试套件缺失或重复')
    listed = {f['path'] for f in files}
    for suite in manifest['suites']:
        if suite['name'].startswith('upgrade-') and not all(suite.get(key) for key in ('apk', 'testApk', 'legacyApk', 'legacyTestApk')):
            raise ValueError('升级套件缺少旧包或新包')
        for key in ('testApk', 'apk', 'legacyApk', 'legacyTestApk'):
            if suite.get(key) and suite[key] not in listed:
                raise ValueError('测试引用未声明 APK')
        for key in ('package', 'testPackage', 'runner'):
            if not re.fullmatch(r'[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+', suite[key]):
                raise ValueError(f'非法 instrumentation 标识: {key}')
    return manifest


def apk_info(aapt, apk):
    badging = subprocess.check_output([aapt, 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not match:
        raise ValueError(f'无法读取 APK 包名: {apk}')
    return match.group(1)


def create(root, destination, sha, run_id, attempt, aapt):
    if destination.exists():
        raise ValueError('候选目录已存在；使用新的目录避免混入旧文件')
    destination.mkdir(parents=True)
    suites = []
    definitions = [
        ('app', 'app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),
        ('library', None, 'library-android/build/outputs/apk/androidTest/debug/library-android-debug-androidTest.apk'),
        ('consumer-minified', 'fixtures/consumer/app/build/outputs/apk/minified/app-minified.apk', 'fixtures/consumer/app/build/outputs/apk/androidTest/minified/app-minified-androidTest.apk'),
    ]
    for name, app, test in definitions:
        test_path = root / test
        test_package = apk_info(aapt, test_path)
        package = apk_info(aapt, root / app) if app else test_package
        suite = {'name': name, 'package': package, 'testPackage': test_package,
                 'runner': 'androidx.test.runner.AndroidJUnitRunner', 'testApk': f'apk/{name}-test.apk'}
        (destination / 'apk').mkdir(exist_ok=True)
        shutil.copyfile(test_path, destination / suite['testApk'])
        if app:
            suite['apk'] = f'apk/{name}.apk'
            shutil.copyfile(root / app, destination / suite['apk'])
        suites.append(suite)
    for variant in ('debug', 'release'):
        name = f'upgrade-{variant}'
        suite = {'name': name, 'runner': 'androidx.test.runner.AndroidJUnitRunner'}
        for stage in ('legacy', 'current'):
            for test in (False, True):
                folder = f'androidTest/{variant}' if test else variant
                filename = f'app-{variant}' + ('-androidTest' if test else '') + '.apk'
                source = root / f'fixtures/upgrade/build/{stage}/app/outputs/apk/{folder}/{filename}'
                key = ('legacyTestApk' if test else 'legacyApk') if stage == 'legacy' else ('testApk' if test else 'apk')
                suite[key] = f'apk/{name}-{stage}' + ('-test' if test else '') + '.apk'
                package = apk_info(aapt, source)
                expected = f'com.logan.multiurlmanager.upgrade.{variant}' + ('.test' if test else '')
                if package != expected:
                    raise ValueError('升级前后必须保持相同的夹具包名')
                suite['testPackage' if test else 'package'] = package
                shutil.copyfile(source, destination / suite[key])
        suites.append(suite)
    maven = root / 'build/ci/maven'
    if not list(maven.rglob('*.aar')) or not list(maven.rglob('*.pom')) or not list(maven.rglob('*-sources.jar')):
        raise ValueError('本地 Maven 候选缺少 AAR/POM/sources')
    shutil.copytree(maven, destination / 'maven', symlinks=False)
    files = [{'path': p.relative_to(destination).as_posix(), 'sha256': digest(p)}
             for p in sorted(destination.rglob('*')) if p.is_file()]
    manifest = {'schemaVersion': 1, 'sha': sha, 'runId': str(run_id), 'runAttempt': str(attempt),
                'event': os.environ.get('GITHUB_EVENT_NAME', 'local'),
                'prHead': os.environ.get('PR_HEAD_SHA', ''), 'version': os.environ['RELEASE_VERSION'],
                'toolchain': {'jdk': '17', 'gradle': '8.14.3', 'compileSdk': 36},
                'suites': suites, 'files': files}
    (destination / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    verify(destination, sha, run_id, attempt)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('create', 'verify'))
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--attempt', required=True)
    parser.add_argument('--aapt')
    args = parser.parse_args()
    if args.mode == 'verify':
        verify(args.candidate, args.sha, args.run_id, args.attempt)
    else:
        create(args.root, args.candidate, args.sha, args.run_id, args.attempt, args.aapt)
    print('候选身份与文件摘要校验通过')


if __name__ == '__main__':
    main()
