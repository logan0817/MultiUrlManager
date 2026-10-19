#!/usr/bin/env python3
"""签名并发布已经通过设备验收的固定 Maven 文件，不重新构建。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

from artifacts import verify

BASE = 'https://central.sonatype.com/api/v1/publisher/'


def request(path, data=b'', content_type='application/json', method='POST'):
    credentials = base64.b64encode(f'{os.environ["CENTRAL_USERNAME"]}:{os.environ["CENTRAL_PASSWORD"]}'.encode()).decode()
    req = urllib.request.Request(BASE + path, data=data if method == 'POST' else None, method=method, headers={
        'Authorization': f'Bearer {credentials}', 'Content-Type': content_type})
    return urllib.request.urlopen(req, timeout=120).read()


def payload_files(maven, version):
    directory = maven / 'io/github/logan0817/multiurlmanager' / version
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('只允许正式版本发布')
    required = [f'multiurlmanager-{version}{suffix}' for suffix in ('.aar', '.pom', '-sources.jar', '-javadoc.jar')]
    if any(not (directory / name).is_file() for name in required):
        raise ValueError('发布候选缺少 AAR/POM/sources/javadoc')
    files = [file for file in sorted(directory.iterdir()) if file.suffix in ('.aar', '.pom', '.jar', '.module')]
    if any(file.name not in required + [f'multiurlmanager-{version}.module'] for file in files):
        raise ValueError('候选版本目录包含未知发布文件')
    pom = ET.parse(directory / f'multiurlmanager-{version}.pom').getroot()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    if tuple(pom.findtext(f'm:{key}', namespaces=namespace) for key in ('groupId', 'artifactId', 'version')) != (
            'io.github.logan0817', 'multiurlmanager', version):
        raise ValueError('POM 坐标与发布目标不一致')
    return files


def verify_deployment_identity(status, deployment, version):
    expected = f'pkg:maven/io.github.logan0817/multiurlmanager@{version}'
    purls = status.get('purls')
    # Central 可同时报告基础坐标与 AAR 类型坐标；两者都必须属于同一受测版本。
    if (status.get('deploymentId') != deployment or not isinstance(purls, list) or not purls
            or any(not isinstance(purl, str) or purl not in (expected, expected + '?type=aar') for purl in purls)
            or len(set(purls)) != len(purls)):
        raise ValueError('部署 ID 或坐标不匹配，禁止继续发布')


def signing_fingerprint():
    value = os.environ.get('SIGNING_FINGERPRINT', '').strip().upper()
    if not re.fullmatch(r'(?:[0-9A-F]{40}|[0-9A-F]{64})', value):
        raise ValueError('SIGNING_FINGERPRINT 必须为预期发布密钥的完整指纹')
    return value


def verify_signature(gpg, environment, signature, file, fingerprint):
    result = subprocess.run([gpg, '--batch', '--status-fd', '1', '--verify', str(signature), str(file)],
                            env=environment, capture_output=True, check=True, text=True, timeout=30)
    # 签名子钥与主钥的完整指纹都来自 GPG 验签结果，不能以导入成功代替签名身份核对。
    signatures = [line.split() for line in result.stdout.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
    if len(signatures) != 1 or fingerprint not in (signatures[0][2], signatures[0][-1]):
        raise ValueError('签名身份与预期完整指纹不一致')


def create_bundle(files, maven, destination, mode='memory'):
    fingerprint = signing_fingerprint()
    if mode not in ('memory', 'gpg'):
        raise ValueError('签名模式只能为 memory 或 gpg')
    if destination.exists():
        raise ValueError('签名包已存在；先核对旧包，不自动覆盖')
    gpg = os.environ.get('GPG_EXECUTABLE', 'gpg')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='central-signing-') as temp:
        environment = dict(os.environ)
        if mode == 'memory':
            gnupg = Path(temp) / 'gnupg'
            gnupg.mkdir(mode=0o700)
            environment['GNUPGHOME'] = str(gnupg)
            subprocess.run([gpg, '--batch', '--import'],
                           input=base64.b64decode(os.environ['SIGNING_KEY_BASE64'], validate=True),
                           env=environment, capture_output=True, check=True, timeout=30)
        bundle = Path(temp) / 'central.zip'
        with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in files:
                relative = file.relative_to(maven).as_posix()
                signature = Path(temp) / (file.name + '.asc')
                command = [gpg, '--batch', '--yes', '--local-user', fingerprint]
                if mode == 'memory':
                    command += ['--pinentry-mode', 'loopback', '--passphrase-fd', '0']
                command += ['--armor', '--detach-sign', '--output', str(signature), str(file)]
                # 本机模式沿用 GPG agent；云端口令仅通过 stdin 传递，不进入命令行或日志。
                subprocess.run(command, input=os.environ['SIGNING_PASSWORD'].encode() if mode == 'memory' else None,
                               env=environment, capture_output=True, check=True, timeout=120)
                verify_signature(gpg, environment, signature, file, fingerprint)
                for name, content in ((relative, file.read_bytes()), (relative + '.asc', signature.read_bytes())):
                    archive.writestr(name, content)
                    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                        archive.writestr(name + '.' + algorithm, hashlib.new(algorithm, content).hexdigest())
        # 完整签名和验签成功后才生成交付文件；不把半成品当作可上传包。
        destination.write_bytes(bundle.read_bytes())
    return fingerprint


def verify_deployment_signatures(files, maven, deployment):
    """恢复已有 deployment 时也核对签名身份，不能只核对未签名内容。"""
    fingerprint = signing_fingerprint()
    gpg = os.environ.get('GPG_EXECUTABLE', 'gpg')
    with tempfile.TemporaryDirectory(prefix='central-verify-') as temp:
        gnupg = Path(temp) / 'gnupg'
        gnupg.mkdir(mode=0o700)
        environment = dict(os.environ, GNUPGHOME=str(gnupg))
        subprocess.run([gpg, '--batch', '--import'],
                       input=base64.b64decode(os.environ['SIGNING_KEY_BASE64'], validate=True),
                       env=environment, capture_output=True, check=True, timeout=30)
        for file in files:
            relative = file.relative_to(maven).as_posix()
            signature = Path(temp) / (file.name + '.asc')
            signature.write_bytes(request(f'deployment/{deployment}/download/{relative}.asc', method='GET'))
            verify_signature(gpg, environment, signature, file, fingerprint)


def prepare_local(candidate, version, output):
    manifest = json.loads((candidate / 'manifest.json').read_text())
    manifest = verify(candidate, manifest['sha'], manifest['runId'], manifest['runAttempt'])
    if manifest['version'] != version:
        raise ValueError('离线候选版本与当前版本不一致')
    if output.resolve().is_relative_to(candidate.resolve()):
        raise ValueError('签名包不能写入已封存的候选目录')
    files = payload_files(candidate / 'maven', version)
    fingerprint = create_bundle(files, candidate / 'maven', output, mode='gpg')
    print(f'离线签名包已生成并验签：{output}；指纹 {fingerprint}；未上传，未证明云端 CI 通过')


def main():
    action = os.environ.get('RELEASE_ACTION', 'upload')
    deployment = os.environ.get('DEPLOYMENT_ID', '').strip()
    if action not in ('upload', 'publish'):
        raise ValueError('RELEASE_ACTION 必须为 upload 或 publish')
    if action == 'publish' and not deployment:
        raise ValueError('正式发布必须指定已经上传验收的 deployment ID，禁止隐式上传')
    candidate = Path('build/ci/candidate')
    manifest = verify(candidate, os.environ['CANDIDATE_SHA'], os.environ['CANDIDATE_RUN_ID'], os.environ['CANDIDATE_ATTEMPT'])
    version = os.environ['RELEASE_VERSION']
    if manifest['version'] != version or manifest['event'] != 'push':
        raise ValueError('候选版本或来源不符')
    files = payload_files(candidate / 'maven', version)
    report = Path('build/ci/release')
    report.mkdir(parents=True, exist_ok=True)
    if deployment and not re.fullmatch(r'[0-9a-fA-F-]{36}', deployment):
        raise ValueError('非法 deployment ID')
    if not deployment:
        # 网络写入不重试；中断后先使用 deployment ID 恢复查询。
        bundle = report / 'central.zip'
        fingerprint = create_bundle(files, candidate / 'maven', bundle)
        (report / 'signing-fingerprint.txt').write_text(fingerprint + '\n')
        boundary = uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central.zip"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n').encode() + bundle.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        deployment = request('upload?' + urllib.parse.urlencode({'name': f'io.github.logan0817-MultiUrlManager-{version}', 'publishingType': 'USER_MANAGED'}),
                             body, f'multipart/form-data; boundary={boundary}').decode().strip()
        if not re.fullmatch(r'[0-9a-fA-F-]{36}', deployment):
            raise ValueError('Central 没有返回合法 deployment ID；查询 Portal 后再操作')
    (report / 'deployment-id.txt').write_text(deployment + '\n')
    print(f'Central deployment ID: {deployment}', flush=True)
    deadline = time.monotonic() + 900
    publish_requested = False
    while time.monotonic() < deadline:
        status = json.loads(request('status?' + urllib.parse.urlencode({'id': deployment})))
        (report / 'status.json').write_text(json.dumps(status, indent=2) + '\n')
        state = status['deploymentState']
        print(f'Central state: {state}', flush=True)
        if state == 'FAILED':
            raise RuntimeError('Central 校验失败，参见保留的 status.json；禁止重传同版本')
        if state in ('VALIDATED', 'PUBLISHED'):
            verify_deployment_identity(status, deployment, version)
        if state == 'VALIDATED':
            for file in files:
                relative = file.relative_to(candidate / 'maven').as_posix()
                content = request(f'deployment/{deployment}/download/{relative}', method='GET')
                if hashlib.sha256(content).digest() != hashlib.sha256(file.read_bytes()).digest():
                    raise ValueError('部署内容与受测候选不一致，禁止继续发布')
            verify_deployment_signatures(files, candidate / 'maven', deployment)
            if action == 'upload':
                print('上传验收完成，停留在 VALIDATED；正式发布须另选 publish 并指定 deployment ID')
                return
            if not publish_requested:
                request(f'deployment/{deployment}')
                publish_requested = True
        elif state == 'PUBLISHED':
            break
        elif state not in ('PENDING', 'VALIDATING', 'PUBLISHING'):
            raise RuntimeError(f'未知 Central 状态 {state}')
        time.sleep(15)
    else:
        raise TimeoutError(f'发布尚未确认，使用 deployment ID {deployment} 查询，不能重复上传')
    # PUBLISHED 之后还要验证公共 Maven 仓库实际提供的是同一文件。
    for file in files:
        relative = file.relative_to(candidate / 'maven').as_posix()
        url = 'https://repo.maven.apache.org/maven2/' + relative
        public = urllib.request.urlopen(url, timeout=60).read()
        if hashlib.sha256(public).hexdigest() != hashlib.sha256(file.read_bytes()).hexdigest():
            raise RuntimeError(f'公开产物摘要不匹配: {file.name}')
    (report / 'public-verification.txt').write_text(f'{version}: 所有公开 Maven 文件与候选 SHA-256 一致\n')
    print('已验证 Maven Central 公开文件与受测候选一致')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only', action='store_true', help='仅用本机 GPG 签名并验签，不读取 Central 凭据或联网')
    parser.add_argument('--candidate', type=Path, default=Path('build/ci/candidate'))
    parser.add_argument('--version')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.prepare_only:
        if not args.version or not args.output:
            parser.error('--prepare-only 必须指定 --version 和 --output')
        prepare_local(args.candidate, args.version, args.output)
    else:
        if args.version or args.output or args.candidate != Path('build/ci/candidate'):
            parser.error('远端流程仅接受固定 CI 候选，不接受离线参数')
        main()
