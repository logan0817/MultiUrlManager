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
import xml.etree.ElementTree as ET


def release_artifacts(version):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-SNAPSHOT)?", version)
    if not match:
        raise ValueError("非法版本")
    return ("multiurlmanager", "multiurlmanager-okhttp") if tuple(map(int, match.groups())) >= (1, 2, 0) else ("multiurlmanager",)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_path(root, name):
    path = root / name
    if not name or Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink():
        raise ValueError(f'非法产物路径: {name}')
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('产物路径越界')
    return path


def publication_files(directory, artifact, version, sealed=False):
    """按 publication 元数据选择完整单轮文件；封存后禁止夹带其他 snapshot。"""
    required = {('', 'aar'), ('', 'pom'), ('sources', 'jar'), ('javadoc', 'jar')}
    selected = []
    resolved = version
    if version.endswith('-SNAPSHOT'):
        metadata = safe_path(directory, 'maven-metadata.xml')
        if not metadata.is_file():
            raise ValueError('SNAPSHOT 候选缺少 Maven metadata')
        root = ET.parse(metadata).getroot()
        if tuple(root.findtext(key) for key in ('groupId', 'artifactId', 'version')) != (
                'io.github.logan0817', artifact, version):
            raise ValueError('SNAPSHOT metadata 坐标不一致')
        timestamp = root.findtext('versioning/snapshot/timestamp', '')
        number = root.findtext('versioning/snapshot/buildNumber', '')
        if not re.fullmatch(r'\d{8}\.\d{6}', timestamp) or not re.fullmatch(r'[1-9]\d*', number):
            raise ValueError('非法 SNAPSHOT timestamp/buildNumber')
        updated = timestamp.replace('.', '')
        if root.findtext('versioning/lastUpdated') != updated:
            raise ValueError('SNAPSHOT metadata 时间不一致')
        base_version = version.removesuffix('-SNAPSHOT')
        resolved = f'{base_version}-{timestamp}-{number}'
        entries = {}
        for entry in root.findall('versioning/snapshotVersions/snapshotVersion'):
            key = (entry.findtext('classifier', ''), entry.findtext('extension', ''))
            if key in entries or key not in required | {('', 'module')}:
                raise ValueError('SNAPSHOT metadata 包含重复或未知 classifier/extension')
            if entry.findtext('value') != resolved or entry.findtext('updated') != updated:
                raise ValueError('SNAPSHOT metadata 混入其他发布轮次')
            entries[key] = entry
        if not required <= entries.keys():
            raise ValueError('SNAPSHOT metadata 缺少必需 classifier/extension')
        for classifier, extension in sorted(entries):
            suffix = f'-{classifier}' if classifier else ''
            selected.append(safe_path(directory, f'{artifact}-{resolved}{suffix}.{extension}'))
        selected.append(metadata)
        # metadata 陈旧时不能退回旧完整轮次，也不能从磁盘猜测补齐半次发布。
        pattern = re.compile(re.escape(f'{artifact}-{base_version}-') +
                             r'(\d{8}\.\d{6})-([1-9]\d*)(?:-(?:sources|javadoc))?\.(?:aar|pom|jar|module)(?:\.(?:md5|sha1|sha256|sha512))?')
        for path in directory.iterdir():
            match = pattern.fullmatch(path.name)
            if match and (match.group(1), int(match.group(2))) > (timestamp, int(number)):
                raise ValueError('SNAPSHOT metadata 陈旧，发现更新发布文件')
    else:
        for classifier, extension in sorted(required):
            suffix = f'-{classifier}' if classifier else ''
            selected.append(safe_path(directory, f'{artifact}-{version}{suffix}.{extension}'))
        module = safe_path(directory, f'{artifact}-{version}.module')
        if module.is_file():
            selected.append(module)
    if any(not path.is_file() for path in selected):
        raise ValueError(f'候选缺少 {artifact} AAR/POM/sources/javadoc/metadata')
    pom = ET.parse(directory / f'{artifact}-{resolved}.pom').getroot()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    if tuple(pom.findtext(f'm:{key}', namespaces=namespace) for key in ('groupId', 'artifactId', 'version')) != (
            'io.github.logan0817', artifact, version):
        raise ValueError('候选 POM 坐标不一致')
    module = directory / f'{artifact}-{resolved}.module'
    if module in selected:
        component = json.loads(module.read_text()).get('component', {})
        if tuple(component.get(key) for key in ('group', 'module', 'version')) != (
                'io.github.logan0817', artifact, version):
            raise ValueError('Gradle module metadata 坐标不一致')
    # 保留并验证 Gradle 实际生成的摘要；候选自己的 manifest 再对所有封存文件做 SHA-256。
    for path in list(selected):
        for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
            checksum = safe_path(directory, path.name + '.' + algorithm)
            if checksum.exists():
                if checksum.read_text().strip().lower() != hashlib.new(algorithm, path.read_bytes()).hexdigest():
                    raise ValueError('Maven 文件 checksum 不匹配')
                selected.append(checksum)
    if sealed and {path.name for path in directory.iterdir()} != {path.name for path in selected}:
        raise ValueError('封存 Maven 目录混入其他发布轮次或未知文件')
    return selected, resolved


def verify(root, sha, run_id, attempt):
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('schemaVersion') not in (1, 2, 3):
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
    expected = {'app', 'library', 'consumer-minified', 'upgrade-debug', 'upgrade-release'}
    if manifest['schemaVersion'] >= 2:
        expected |= {'upgrade11-debug', 'upgrade11-release', 'okhttp'}
    if manifest['schemaVersion'] >= 3:
        expected |= {'upgrade12-debug', 'upgrade12-release'}
    if manifest.get('version') and tuple(map(int, manifest['version'].split('-')[0].split('.')[:2])) >= (1, 3) and manifest['schemaVersion'] < 3:
        raise ValueError('1.3 起必须包含 1.2 升级套件')
    if names != expected or len(manifest['suites']) != len(expected):
        raise ValueError('必需的仪器测试套件缺失或重复')
    listed = {f['path'] for f in files}
    if manifest.get('version') and len(release_artifacts(manifest['version'])) == 2:
        if manifest['schemaVersion'] < 2:
            raise ValueError('1.2 起必须使用完整新候选套件')
        snapshots = {}
        publication_paths = set()
        for artifact in release_artifacts(manifest['version']):
            directory = root / 'maven/io/github/logan0817' / artifact / manifest['version']
            if not directory.is_dir():
                raise ValueError('候选缺少本轮必需的 Maven 坐标或文件')
            selected, resolved = publication_files(directory, artifact, manifest['version'], sealed=True)
            publication_paths.update(path.relative_to(root).as_posix() for path in selected)
            if manifest['version'].endswith('-SNAPSHOT'):
                snapshots[artifact] = resolved
        if {path for path in listed if path.startswith('maven/')} != publication_paths:
            raise ValueError('候选 Maven 文件混入其他坐标或版本')
        if snapshots and manifest.get('snapshotVersions') != snapshots:
            raise ValueError('封存 SNAPSHOT 轮次与候选身份不一致')
    for suite in manifest['suites']:
        if suite['name'].startswith(('upgrade-', 'upgrade11-', 'upgrade12-')) and not all(suite.get(key) for key in ('apk', 'testApk', 'legacyApk', 'legacyTestApk')):
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
        ('okhttp', None, 'library-okhttp/build/outputs/apk/androidTest/debug/library-okhttp-debug-androidTest.apk'),
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
    version = os.environ['RELEASE_VERSION']
    baselines = [('', ('legacy', 'current')), ('11', ('legacy11', 'current11'))]
    modern = tuple(map(int, version.split('-')[0].split('.')[:2])) >= (1, 3)
    if modern:
        baselines.append(('12', ('legacy12', 'current12')))
    for baseline, stages in baselines:
        for variant in ('debug', 'release'):
            name = f'upgrade{baseline}-{variant}'
            suite = {'name': name, 'runner': 'androidx.test.runner.AndroidJUnitRunner'}
            for stage in stages:
                old = stage.startswith('legacy')
                for test in (False, True):
                    folder = f'androidTest/{variant}' if test else variant
                    filename = f'app{baseline}-{variant}' + ('-androidTest' if test else '') + '.apk'
                    source = root / f'fixtures/upgrade/build/{stage}/app/outputs/apk/{folder}/{filename}'
                    key = ('legacyTestApk' if test else 'legacyApk') if old else ('testApk' if test else 'apk')
                    suite[key] = f'apk/{name}-{stage}' + ('-test' if test else '') + '.apk'
                    package = apk_info(aapt, source)
                    expected = f'com.logan.multiurlmanager.upgrade{baseline}.{variant}' + ('.test' if test else '')
                    if package != expected:
                        raise ValueError('升级前后必须保持相同的夹具包名')
                    suite['testPackage' if test else 'package'] = package
                    shutil.copyfile(source, destination / suite[key])
            suites.append(suite)
    # 只封存本轮版本的实际坐标，避免共享文件仓库的旧版本混进候选。
    version = os.environ['RELEASE_VERSION']
    snapshots = {}
    for artifact in release_artifacts(version):
        relative = Path('io/github/logan0817') / artifact / version
        source = root / 'build/ci/maven' / relative
        selected, resolved = publication_files(source, artifact, version)
        target = destination / 'maven' / relative
        target.mkdir(parents=True)
        for path in selected:
            shutil.copyfile(path, target / path.name)
        if version.endswith('-SNAPSHOT'):
            snapshots[artifact] = resolved
    files = [{'path': p.relative_to(destination).as_posix(), 'sha256': digest(p)}
             for p in sorted(destination.rglob('*')) if p.is_file()]
    manifest = {'schemaVersion': 3 if modern else 2, 'sha': sha, 'runId': str(run_id), 'runAttempt': str(attempt),
                'event': os.environ.get('GITHUB_EVENT_NAME', 'local'),
                'prHead': os.environ.get('PR_HEAD_SHA', ''), 'version': os.environ['RELEASE_VERSION'],
                'toolchain': {'jdk': '17', 'gradle': '8.14.3', 'compileSdk': 36},
                'suites': suites, 'files': files}
    if snapshots:
        manifest['snapshotVersions'] = snapshots
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
