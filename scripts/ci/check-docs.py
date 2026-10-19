#!/usr/bin/env python3
"""校验文档链接、图片、围栏示例及本地配置；不访问外部站点。"""
import json
from pathlib import Path
import re
from urllib.parse import unquote
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def check_markdown(path):
    text = path.read_text(encoding='utf-8')
    failures = []
    if not text.endswith('\n'):
        failures.append(f'{path}: 缺少末尾换行')
    if len(re.findall(r'^```', text, re.M)) % 2:
        failures.append(f'{path}: 代码围栏未闭合')
    targets = re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text)
    targets += re.findall(r'<(?:img|a)\b[^>]*\b(?:src|href)=["\']([^"\']+)["\']', text)
    for target in targets:
        if re.match(r'[a-zA-Z][\w+.-]*:', target) or target.startswith('#'):
            continue
        file = unquote(target.split('#', 1)[0])
        if file and not (path.parent / file).exists():
            failures.append(f'{path}: 无效本地链接或图片 {target}')
    for language, source in re.findall(r'^```(json|xml)\s*\n(.*?)^```\s*$', text, re.M | re.S):
        try:
            if language == 'json':
                json.loads(source)
            else:
                ET.fromstring(source)
        except (ValueError, ET.ParseError) as error:
            failures.append(f'{path}: {language} 示例无法解析: {error}')
    return failures


def main():
    paths = [*ROOT.glob('*.md'), *ROOT.joinpath('docs').rglob('*.md')]
    failures = [error for path in paths for error in check_markdown(path)]
    for path in [*ROOT.glob('*/src/*/assets/*.json'), *ROOT.joinpath('docs').rglob('*.json')]:
        try:
            json.loads(path.read_text(encoding='utf-8'))
        except (ValueError, UnicodeError) as error:
            failures.append(f'{path}: {error}')
    api_source = ROOT / 'library-android/src/main/java/com/logan/multiurlmanager/library'
    sources = (api_source / 'BaseUrlManager.kt').read_text() + (api_source / 'UrlEnvironment.kt').read_text()
    for symbol in ('setDefaultEnvironment', 'snapshot', 'effectiveUrls', 'saveAsync', 'addListener', 'removeListener'):
        if f'fun {symbol}(' not in sources:
            failures.append(f'文档引用了不存在的 API: {symbol}')
    if 'class BaseUrlManagerContract ' not in (api_source / 'BaseUrlManagerContract.kt').read_text():
        failures.append('文档引用的 Activity Result Contract 不存在')
    properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    for name in ('README.md', 'README_EN.md'):
        if f'io.github.logan0817:multiurlmanager:{properties["APP_VERSION_NAME"]}' not in (ROOT / name).read_text():
            failures.append(f'{name}: 依赖版本与源码不一致')
    if failures:
        raise SystemExit('\n'.join(failures))
    print(f'{len(paths)} 份文档、本地链接/图片、JSON/XML 示例及本地 JSON 校验通过')


if __name__ == '__main__':
    main()
