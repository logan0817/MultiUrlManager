#!/usr/bin/env python3
"""安装同轮 APK，执行全部必需套件，并保留原始输出与 logcat。"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from artifacts import verify
from instrumentation import validate_output
from upgrade import run_upgrade


def command(args, timeout=120):
    result = subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'adb {args[0]} 失败: {result.stdout}')
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--reports', type=Path, default=Path('build/ci/device'))
    args = parser.parse_args()
    manifest = verify(args.candidate, os.environ['GITHUB_SHA'], os.environ['GITHUB_RUN_ID'],
                      os.environ['GITHUB_RUN_ATTEMPT'])
    args.reports.mkdir(parents=True, exist_ok=True)
    failures = []
    for suite in manifest['suites']:
        name = suite['name']
        cleanup_failed = False
        try:
            command(['logcat', '-c'])
            if name.startswith('upgrade-'):
                count = run_upgrade(args.candidate, suite, args.reports, command)
                print(f'{name}: {count} upgrade stages passed')
            else:
                for key in ('apk', 'testApk'):
                    if suite.get(key):
                        output = command(['install', '-r', '-t', str(args.candidate / suite[key])])
                        if 'Success' not in output:
                            raise RuntimeError(f'APK 安装没有成功终态: {output}')
                output = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'clearPackageData', 'true',
                                  f'{suite["testPackage"]}/{suite["runner"]}'], timeout=900)
                (args.reports / f'{name}.txt').write_text(output)
                count = validate_output(output)
                print(f'{name}: {count} tests passed')
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
            if isinstance(error, subprocess.TimeoutExpired):
                partial = error.stdout or b''
                (args.reports / f'{name}-timeout.txt').write_bytes(partial if isinstance(partial, bytes) else partial.encode())
            failures.append(name)
            (args.reports / f'{name}-error.txt').write_text(str(error))
            print(f'{name}: {error}', file=sys.stderr)
        finally:
            # adb 超时只结束主机进程，设备端 instrumentation 仍可能运行并占用前台。
            # 必须在下一套件前停止本套件；失败清理也不能被当作测试通过。
            for package in dict.fromkeys((suite['package'], suite['testPackage'])):
                try:
                    command(['shell', 'am', 'force-stop', package], timeout=30)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    failures.append(name)
                    cleanup_failed = True
                    with (args.reports / f'{name}-cleanup-error.txt').open('a') as report:
                        report.write(f'{package}: {error}\n')
                    print(f'{name} cleanup: {error}', file=sys.stderr)
            try:
                result = subprocess.run(['adb', 'logcat', '-d', '-t', '3000'], capture_output=True, text=True, timeout=30)
                (args.reports / f'{name}-logcat.txt').write_text(result.stdout + result.stderr)
            except (OSError, subprocess.TimeoutExpired) as error:
                (args.reports / f'{name}-diagnostic-error.txt').write_text(str(error))
        if cleanup_failed:
            break
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
