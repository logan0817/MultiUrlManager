import hashlib
from pathlib import Path
import tempfile
import unittest

from test_ci import successful
from upgrade import run_upgrade


class UpgradeDriverTest(unittest.TestCase):
    def test_old_asset_is_frozen_from_801d130(self):
        asset = Path(__file__).resolve().parents[2] / 'fixtures/upgrade/app/src/legacy/assets/base_urls_config.json'
        self.assertEqual(hashlib.sha256(asset.read_bytes()).hexdigest(),
                         '375d67b9b02ca7b1d892ce08a444486dbfe88e38a1e12bdf5ddfb8ac1a60fe3d')

    def test_real_upgrade_plan_preserves_data_between_versions_and_restarts(self):
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            if args[:4] == ['shell', 'pm', 'list', 'packages']:
                return 'package:com.logan.multiurlmanager.upgrade.debug\n'
            if args[:3] == ['shell', 'am', 'instrument']:
                return successful()
            return 'Success'
        suite = {'name': 'upgrade-debug', 'package': 'com.logan.multiurlmanager.upgrade.debug',
                 'testPackage': 'com.logan.multiurlmanager.upgrade.debug.test', 'runner': 'test.Runner',
                 'apk': 'new.apk', 'testApk': 'new-test.apk', 'legacyApk': 'old.apk', 'legacyTestApk': 'old-test.apk'}
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(run_upgrade(Path(temp), suite, Path(temp), command), 9)
            self.assertEqual(len(list(Path(temp).glob('*.txt'))), 9)
        self.assertEqual(len([c for c in calls if c[0] == 'uninstall']), 3)
        self.assertFalse(any('clear' in c for c in calls))
        stages = [c for c in calls if c[0] in ('install', 'uninstall') or c[:3] == ['shell', 'am', 'instrument']]
        for start in range(0, len(stages), 8):
            group = stages[start:start + 8]
            self.assertEqual(group[0][0], 'uninstall')
            self.assertTrue(group[1][-1].endswith('old.apk'))
            self.assertEqual(group[3][:3], ['shell', 'am', 'instrument'])
            self.assertEqual(group[4][:3], ['install', '-r', '-t'])
            self.assertTrue(group[4][-1].endswith('new.apk'))
            self.assertEqual(group[6][:3], ['shell', 'am', 'instrument'])
            self.assertEqual(group[7][:3], ['shell', 'am', 'instrument'])

    def test_refuses_to_clear_real_demo(self):
        with self.assertRaises(ValueError):
            run_upgrade(Path('.'), {'package': 'com.logan.multiurlmanager'}, Path('.'), lambda *args: self.fail())
