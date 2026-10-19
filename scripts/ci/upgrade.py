"""旧发行库写入真实存储，覆盖安装新版后跨进程验证；仅清理专用升级夹具。"""
from instrumentation import validate_output


def run_upgrade(candidate, suite, reports, command):
    package = suite['package']
    if package not in ('com.logan.multiurlmanager.upgrade.debug', 'com.logan.multiurlmanager.upgrade.release'):
        raise ValueError('升级测试只允许清理专用夹具包')
    count = 0
    for scenario in ('asset', 'custom', 'code'):
        # 上一个场景为新版，先卸载仅属于夹具的应用，下一场景才能重新安装 versionCode=1。
        installed = command(['shell', 'pm', 'list', 'packages', package]).splitlines()
        if 'package:' + package in installed:
            if 'Success' not in command(['uninstall', package]):
                raise RuntimeError('无法清理上一个升级夹具场景')
        for stage, keys in (('legacy', ('legacyApk', 'legacyTestApk')), ('current', ('apk', 'testApk'))):
            for key in keys:
                if 'Success' not in command(['install', '-r', '-t', str(candidate / suite[key])]):
                    raise RuntimeError('升级夹具安装失败')
            # current 使用 -r 保留旧数据，绝不能 pm clear；第二次调用验证新进程不会重复迁移。
            for attempt in range(1 if stage == 'legacy' else 2):
                output = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'scenario', scenario,
                                  '-e', 'attempt', str(attempt),
                                  f'{suite["testPackage"]}/{suite["runner"]}'], timeout=120)
                (reports / f'{suite["name"]}-{scenario}-{stage}-{attempt}.txt').write_text(output)
                if validate_output(output) != 1:
                    raise ValueError('每阶段必须完整执行一个升级测试')
                count += 1
                command(['shell', 'am', 'force-stop', package])
                command(['shell', 'am', 'force-stop', suite['testPackage']])
    return count
