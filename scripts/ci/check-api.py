#!/usr/bin/env python3
"""核验已发布 1.0.6 的公开 JVM ABI；只允许新增 API，不允许删除旧入口。"""
import argparse
import hashlib
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


def verified_baseline(path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        # 完整下载并校验成功后才替换缓存，避免中断留下可复用的坏文件。
        with urllib.request.urlopen(ARTIFACT_URL, timeout=60) as response:
            data = response.read(5 * 1024 * 1024 + 1)
        if hashlib.sha256(data).hexdigest() != ARTIFACT_SHA256:
            raise ValueError('Maven Central 1.0.6 AAR 摘要不匹配')
        path.write_bytes(data)
    if hashlib.sha256(path.read_bytes()).hexdigest() != ARTIFACT_SHA256:
        raise ValueError(f'基线 AAR 摘要不匹配，请检查缓存: {path}')
    return path


def without_generics(text):
    while '<' in text:
        text, changed = re.subn(r'<[^<>]*>', '', text)
        if not changed:
            raise ValueError(f'无法解析 javap 泛型声明: {text}')
    return text


def parse_class(output, class_name):
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
            if not name.startswith('access$') and 'Lkotlin/jvm/internal/DefaultConstructorMarker;' not in descriptor:
                members.append({'kind': 'method' if method else 'field', 'name': name,
                                'descriptor': descriptor, 'static': 'static' in declaration.split()})
            declaration = None
    return {'kind': kind, 'supertypes': sorted(super_types),
            'members': sorted(members, key=lambda item: (item['kind'], item['name'], item['descriptor']))}


def inspect(aar, javap):
    result = {}
    with tempfile.TemporaryDirectory(prefix='multiurlmanager-api-') as directory:
        jar = Path(directory) / 'classes.jar'
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read('classes.jar'))
        with zipfile.ZipFile(jar) as archive:
            entries = set(archive.namelist())
        for name in CLASSES:
            if name.replace('.', '/') + '.class' not in entries:
                continue
            output = subprocess.check_output([javap, '-public', '-s', '-classpath', str(jar), name],
                                             text=True, encoding='utf-8')
            result[name] = parse_class(output, name)
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
    parser.add_argument('--write-baseline', action='store_true', help='从摘要固定的已发布 AAR 重新生成基线')
    args = parser.parse_args()
    java_home = os.environ.get('JAVA_HOME')
    javap = str(Path(java_home) / 'bin/javap') if java_home else shutil.which('javap')
    if not javap:
        raise ValueError('需要 JDK 17 的 javap，请设置 JAVA_HOME')
    published = inspect(verified_baseline(args.baseline_aar), javap)
    if set(published) != set(CLASSES):
        raise ValueError('已发布 AAR 缺少固定基线类，无法建立可信基线')
    document = {'artifact': ARTIFACT_URL, 'sha256': ARTIFACT_SHA256, 'classes': published}
    if args.write_baseline:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'已从固定摘要的 1.0.6 AAR 生成基线: {args.baseline}')
        return
    if json.loads(args.baseline.read_text(encoding='utf-8')) != document:
        raise ValueError('仓库 ABI 基线与已发布 1.0.6 AAR 不一致')
    failures = missing_api(published, inspect(args.aar, javap))
    if failures:
        raise ValueError('旧公开 ABI 缺失或不兼容:\n' + '\n'.join(f'  {entry}' for entry in failures))
    count = sum(len(value['members']) for value in published.values())
    print(f'1.0.6 公开 ABI 校验通过: {len(published)} 个类、{count} 个字段/方法')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, KeyError) as error:
        raise SystemExit(str(error)) from error
