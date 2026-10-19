#!/usr/bin/env python3
"""校验固定版本 actionlint 的摘要后，仅在临时目录运行。"""
import hashlib
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
VERSION = '1.7.7'
# 来自该版本官方 release checksums，不能在运行时信任下载包自带的摘要。
SHA256 = {
    'linux_amd64': '023070a287cd8cccd71515fedc843f1985bf96c436b7effaecce67290e7e0757',
    'linux_arm64': '401942f9c24ed71e4fe71b76c7d638f66d8633575c4016efd2977ce7c28317d0',
    'darwin_amd64': '28e5de5a05fc558474f638323d736d822fff183d2d492f0aecb2b73cc44584f5',
    'darwin_arm64': '2693315b9093aeacb4ebd91a993fea54fc215057bf0da2659056b4bc033873db',
}


def distribution(system, machine):
    architecture = {'x86_64': 'amd64', 'amd64': 'amd64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(machine.lower())
    target = f'{system.lower()}_{architecture}'
    if target not in SHA256:
        raise ValueError(f'actionlint 不支持当前校验平台：{system}/{machine}')
    return target


def main():
    target = distribution(platform.system(), platform.machine())
    workflows = sorted((ROOT / '.github/workflows').glob('*.yml'))
    for workflow in workflows:
        for action in re.findall(r'^\s*-?\s*uses:\s+([^\s#]+)', workflow.read_text(), re.M):
            if not re.fullmatch(r'[\w.-]+/[\w./-]+@[0-9a-f]{40}', action):
                raise SystemExit(f'{workflow}: Action 必须固定完整 SHA: {action}')
    with tempfile.TemporaryDirectory(prefix='workflow-check-') as directory:
        archive = Path(directory) / 'actionlint.tar.gz'
        url = f'https://github.com/rhysd/actionlint/releases/download/v{VERSION}/actionlint_{VERSION}_{target}.tar.gz'
        with urllib.request.urlopen(url, timeout=60) as response:
            archive.write_bytes(response.read())
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256[target]:
            raise SystemExit('actionlint 下载摘要不匹配')
        with tarfile.open(archive) as tar:
            binary = tar.extractfile('actionlint').read()
        executable = Path(directory) / 'actionlint'
        executable.write_bytes(binary)
        executable.chmod(0o700)
        subprocess.run([str(executable), '-color', f'-shellcheck={shutil.which("shellcheck") or ""}', *map(str, workflows)], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
