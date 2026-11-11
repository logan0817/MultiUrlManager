#!/usr/bin/env python3
"""核验已发布 1.0.6/1.1.0/1.2.0 双制品的公开 JVM ABI；只允许新增 API，不允许删除旧入口。"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[2]
ARTIFACT_URL = 'https://repo.maven.apache.org/maven2/io/github/logan0817/multiurlmanager/1.0.6/multiurlmanager-1.0.6.aar'
ARTIFACT_SHA256 = 'cffa13b0bf3fb43c209621e135701d7925024ff83ca5354910ca8aafc790be9e'
PACKAGE = 'com.logan.multiurlmanager.library.'
# 固定基线覆盖源码公开类；不纳入 BuildConfig、匿名 lambda 和编译器访问桥。
CLASSES = [PACKAGE + name for name in (
    'BaseUrlManager', 'BaseUrlManager$Companion', 'BaseUrlManager$Companion$Builder',
    'IBaseUrlManager', 'IBaseUrlManager$DefaultImpls',
    'BaseUrlManagerActivity', 'BaseUrlManagerActivity$Companion',
    'adapter.BaseUrlAdapter', 'adapter.BaseUrlAdapter$Companion',
    'adapter.BaseUrlAdapter$BaseUrlViewHolder', 'adapter.BaseUrlAdapter$HeaderViewHolder',
    'adapter.BaseUrlAdapter$NormalViewHolder',
    'bean.BaseUrl', 'bean.BaseUrl$Creator', 'bean.BaseUrlSection',
    'utils.BaseUrlConfigLoader', 'utils.BaseUrlUtil',
)]


BASELINES = {
    "1.0.6": ARTIFACT_SHA256,
    "1.1.0": "d4237ddba47f663b2c73f80f3a18e380fdea0b62d87ab5eda10813ca39a6e897",
    "1.2.0": "a17a749232ccdeab14c0b209493f10a2281409e8c2d219457d0b5d095a7335b5",
}
MODERN_CLASSES = CLASSES + [PACKAGE + name for name in (
    "UrlEnvironment", "UrlSnapshot", "SaveResult", "SaveResult$Success", "SaveResult$Failure",
    "SaveResult$Reason", "UrlChangeListener", "BaseUrlManagerContract", "BaseUrlManagerResult",
    "BaseUrlManagerActivity$EditorState",
)]


NETWORK_SHA256 = "de6a423019da48c6bb0b2b8221627ad6b8da78f4ea82e3afc76c45d8c017da13"
NETWORK_CLASSES = ["com.logan.multiurlmanager.okhttp." + name for name in ("RouteKey", "RouteContext", "UrlRoutingInterceptor")]


def verified_baseline(path, version="1.0.6", artifact="multiurlmanager"):
    expected = NETWORK_SHA256 if artifact == "multiurlmanager-okhttp" and version == "1.2.0" else BASELINES[version]
    if artifact not in ("multiurlmanager", "multiurlmanager-okhttp") or (artifact == "multiurlmanager-okhttp" and version != "1.2.0"):
        raise ValueError("未知发行坐标")
    url = f"https://repo.maven.apache.org/maven2/io/github/logan0817/{artifact}/{version}/{artifact}-{version}.aar"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        # 完整下载并校验成功后才替换缓存，避免中断留下可复用的坏文件。
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(5 * 1024 * 1024 + 1)
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Maven Central AAR 摘要不匹配')
        path.write_bytes(data)
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f'基线 AAR 摘要不匹配，请检查缓存: {path}')
    return path


def without_generics(text):
    while '<' in text:
        text, changed = re.subn(r'<[^<>]*>', '', text)
        if not changed:
            raise ValueError(f'无法解析 javap 泛型声明: {text}')
    return text


def parse_class(output, class_name, include_defaults=False):
    header = next((line for line in output.splitlines() if line.startswith('public ') and line.endswith(' {')), None)
    if header is None:
        raise ValueError(f'公开类不存在: {class_name}')
    header = without_generics(header)
    kind = 'interface' if ' interface ' in header else 'class'
    # 同时保留父类/接口，防止方法 descriptor 未变但宿主类型关系被破坏。
    super_types = []
    for clause in re.split(r'\b(?:extends|implements)\b', header)[1:]:
        super_types.extend(part.strip() for part in clause.rstrip(' {').split(','))
    members = []
    declaration = None
    for line in output.splitlines():
        line = line.strip()
        if line.startswith('public ') and line.endswith(';'):
            declaration = line
        elif line.startswith('descriptor:') and declaration is not None:
            descriptor = line.removeprefix('descriptor:').strip()
            method = '(' in declaration
            name = declaration.split('(', 1)[0].split()[-1] if method else declaration[:-1].split()[-1]
            if name == class_name:
                name = '<init>'
            if not name.startswith('access$') and (include_defaults or 'Lkotlin/jvm/internal/DefaultConstructorMarker;' not in descriptor):
                members.append({'kind': 'method' if method else 'field', 'name': name,
                                'descriptor': descriptor, 'static': 'static' in declaration.split()})
            declaration = None
    return {'kind': kind, 'supertypes': sorted(super_types),
            'members': sorted(members, key=lambda item: (item['kind'], item['name'], item['descriptor']))}


def inspect(aar, javap, classes=CLASSES, include_defaults=False):
    result = {}
    with tempfile.TemporaryDirectory(prefix='multiurlmanager-api-') as directory:
        jar = Path(directory) / 'classes.jar'
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read('classes.jar'))
        with zipfile.ZipFile(jar) as archive:
            entries = set(archive.namelist())
        for name in classes:
            if name.replace('.', '/') + '.class' not in entries:
                continue
            output = subprocess.check_output([javap, '-public', '-s', '-classpath', str(jar), name],
                                             text=True, encoding='utf-8')
            result[name] = parse_class(output, name, include_defaults)
    return result


def missing_api(baseline, current):
    failures = []
    for name, previous in baseline.items():
        actual = current.get(name)
        if actual is None:
            failures.append(f'{name}: 公开类缺失')
            continue
        if actual['kind'] != previous['kind']:
            failures.append(f'{name}: class/interface 类型改变')
        for parent in previous['supertypes']:
            if parent not in actual['supertypes']:
                failures.append(f'{name}: 父类型缺失 {parent}')
        for member in previous['members']:
            if member not in actual['members']:
                marker = 'static ' if member['static'] else ''
                failures.append(f'{name}: {marker}{member["kind"]} {member["name"]} {member["descriptor"]}')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aar', type=Path, default=ROOT / 'library-android/build/outputs/aar/library-android-release.aar')
    parser.add_argument('--baseline-aar', type=Path, default=ROOT / 'build/compat-baseline/multiurlmanager-1.0.6.aar')
    parser.add_argument('--baseline', type=Path, default=ROOT / 'docs/api-baseline-1.0.6.json')
    parser.add_argument('--version', choices=tuple(BASELINES), help='缺省检查全部已发行基线（1.2 含网络制品）')
    parser.add_argument('--write-baseline', action='store_true', help='从摘要固定的已发布 AAR 重新生成基线')
    args = parser.parse_args()
    java_home = os.environ.get('JAVA_HOME')
    javap = str(Path(java_home) / 'bin/javap') if java_home else shutil.which('javap')
    if not javap:
        raise ValueError('需要 JDK 17 的 javap，请设置 JAVA_HOME')
    for version in (args.version,) if args.version else BASELINES:
        modern = version != '1.0.6'
        classes = MODERN_CLASSES if modern else CLASSES
        aar = ROOT / f'build/compat-baseline/multiurlmanager-{version}.aar' if modern else args.baseline_aar
        baseline = ROOT / f'docs/api-baseline-{version}.json' if modern else args.baseline
        published = inspect(verified_baseline(aar, version), javap, classes, modern)
        if set(published) != set(classes):
            raise ValueError(f'{version} 已发布 AAR 缺少固定基线类')
        url = f'https://repo.maven.apache.org/maven2/io/github/logan0817/multiurlmanager/{version}/multiurlmanager-{version}.aar'
        document = {'artifact': url, 'sha256': BASELINES[version], 'classes': published}
        if args.write_baseline:
            baseline.parent.mkdir(parents=True, exist_ok=True)
            baseline.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(f'已从固定摘要的 {version} AAR 生成基线: {baseline}')
            continue
        if json.loads(baseline.read_text(encoding='utf-8')) != document:
            raise ValueError(f'仓库 ABI 基线与已发布 {version} AAR 不一致')
        current = inspect(args.aar, javap, classes, modern)
        failures = missing_api(published, current)
        if modern:
            # 新增枚举/封闭分支可能让旧 Kotlin 穷尽 when 在运行时失败。
            if current[PACKAGE + 'SaveResult$Reason'] != published[PACKAGE + 'SaveResult$Reason']:
                failures.append('SaveResult.Reason 必须保持发行分支')
            with zipfile.ZipFile(args.aar) as archive:
                with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as jar:
                    branches = {name for name in jar.namelist() if name.startswith(PACKAGE.replace('.', '/') + 'SaveResult$')}
            if branches != {PACKAGE.replace('.', '/') + 'SaveResult$' + name + '.class' for name in ('Failure', 'Success', 'Reason')}:
                failures.append('SaveResult 封闭分支改变，旧消费者可能无法处理')
        if failures:
            raise ValueError(f'{version} 公开 ABI 缺失或不兼容:\n' + '\n'.join(f'  {entry}' for entry in failures))
        count = sum(len(value['members']) for value in published.values())
        print(f'{version} 公开 ABI 校验通过: {len(published)} 个类、{count} 个字段/方法')

    if args.version in (None, '1.2.0'):
        artifact = 'multiurlmanager-okhttp'
        path = ROOT / 'build/compat-baseline/multiurlmanager-okhttp-1.2.0.aar'
        baseline = ROOT / 'docs/api-baseline-1.2.0-okhttp.json'
        published = inspect(verified_baseline(path, '1.2.0', artifact), javap, NETWORK_CLASSES, True)
        if set(published) != set(NETWORK_CLASSES):
            raise ValueError('1.2.0 网络发行 AAR 缺少公开类')
        document = {'artifact': f'https://repo.maven.apache.org/maven2/io/github/logan0817/{artifact}/1.2.0/{artifact}-1.2.0.aar',
                    'sha256': NETWORK_SHA256, 'classes': published}
        if args.write_baseline:
            baseline.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n')
        else:
            if json.loads(baseline.read_text()) != document:
                raise ValueError('网络 ABI 基线与已发布 1.2.0 不一致')
            current = inspect(ROOT / 'library-okhttp/build/outputs/aar/library-okhttp-release.aar', javap, NETWORK_CLASSES, True)
            failures = missing_api(published, current)
            if failures:
                raise SystemExit('\n'.join(failures))
            print('1.2.0 网络制品 ABI 兼容检查通过')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, KeyError) as error:
        raise SystemExit(str(error)) from error
