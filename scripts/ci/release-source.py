#!/usr/bin/env python3
"""只选择可信 main 上对应 tag、完整成功的 CI 候选。此步骤不持有发布密钥。"""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def select_run(runs, sha):
    eligible = [run for run in runs if run['head_branch'] == 'main' and run['head_sha'] == sha
                and run['conclusion'] == 'success' and run['event'] == 'push']
    if not eligible:
        raise ValueError('对应 main 提交没有完整成功的 CI；不能发布')
    return max(eligible, key=lambda item: item['id'])


def main():
    tag = os.environ['RELEASE_TAG']
    if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
        raise SystemExit('tag 必须为正式版本 vX.Y.Z')
    sha = git('rev-parse', f'refs/tags/{tag}^{{commit}}')
    subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], check=True)
    props = git('show', f'{sha}:gradle.properties')
    version = re.search(r'^APP_VERSION_NAME=(.+)$', props, re.M).group(1).strip()
    if tag != f'v{version}':
        raise SystemExit('tag 与受测版本不一致')
    repository = os.environ['GITHUB_REPOSITORY']
    request = urllib.request.Request(
        f'https://api.github.com/repos/{repository}/actions/workflows/ci.yml/runs?head_sha={sha}&event=push&status=success&per_page=50',
        headers={'Authorization': f'Bearer {os.environ["GITHUB_TOKEN"]}', 'Accept': 'application/vnd.github+json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    runs = json.load(urllib.request.urlopen(request))['workflow_runs']
    run = select_run(runs, sha)
    values = {'sha': sha, 'run_id': str(run['id']), 'attempt': str(run['run_attempt']), 'version': version}
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in values.items():
            output.write(f'{key}={value}\n')
    print(f'选定 {tag} / {sha} / CI run {run["id"]} attempt {run["run_attempt"]}')


if __name__ == '__main__':
    main()
