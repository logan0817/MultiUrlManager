import importlib.util
import hashlib
import json
import io
import os
import subprocess
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

from artifacts import verify
from instrumentation import validate_output


def successful(count=1):
    return ''.join(f'INSTRUMENTATION_STATUS: numtests={count}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n' for _ in range(count)) + f'OK ({count} tests)\nINSTRUMENTATION_CODE: -1\n'


def release_payload(target):
    target.mkdir(parents=True, exist_ok=True)
    for suffix in ('.aar', '.pom', '-sources.jar', '-javadoc.jar'):
        content = (b'<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   b'<groupId>io.github.logan0817</groupId><artifactId>multiurlmanager</artifactId>'
                   b'<version>1.1.0</version></project>') if suffix == '.pom' else b'candidate'
        (target / ('multiurlmanager-1.1.0' + suffix)).write_bytes(content)


class InstrumentationTest(unittest.TestCase):
    def test_success(self):
        self.assertEqual(validate_output(successful(2)), 2)

    def test_failures(self):
        for output in ('', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1\n',
                       successful().replace('STATUS_CODE: 0', 'STATUS_CODE: -2'),
                       successful().replace('STATUS_CODE: 0', 'STATUS_CODE: -1'),
                       successful().replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
                       successful().replace('INSTRUMENTATION_CODE: -1', ''),
                       successful() + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
                       successful().replace('numtests=1', 'numtests=2'),
                       successful() + 'INSTRUMENTATION_CODE: 0\n'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                validate_output(output)


class DeviceRunnerTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('device_runner', Path(__file__).with_name('run-instrumentation.py'))
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)
        self.suites = [{'name': name, 'package': f'{name}.app', 'testPackage': f'{name}.test',
                        'runner': 'test.Runner'} for name in ('first', 'second')]
        self.calls = []

    def run_suites(self, command):
        with patch.dict(os.environ, {'GITHUB_SHA': 'abc', 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}), \
                patch('sys.argv', ['run-instrumentation.py', 'candidate', '--reports', str(self.reports)]), \
                patch.object(self.runner, 'verify', return_value={'suites': self.suites}), \
                patch.object(self.runner, 'command', side_effect=command), \
                patch.object(self.runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'logcat', '')):
            return self.runner.main()

    def test_timeout_preserves_output_and_stops_both_packages_before_next_suite(self):
        def command(args, **kwargs):
            self.calls.append(args)
            if args[:3] == ['shell', 'am', 'instrument']:
                if args[-1].startswith('first.'):
                    raise subprocess.TimeoutExpired(args, 900, output=b'partial instrumentation output')
                return successful()
            return ''
        self.assertTrue(self.run_suites(command))
        instrument = [i for i, args in enumerate(self.calls) if args[:3] == ['shell', 'am', 'instrument']]
        for package in ('first.app', 'first.test'):
            self.assertTrue(instrument[0] < self.calls.index(['shell', 'am', 'force-stop', package]) < instrument[1])
        self.assertEqual((self.reports / 'first-timeout.txt').read_bytes(), b'partial instrumentation output')
        self.assertTrue((self.reports / 'first-logcat.txt').exists())
        self.assertTrue((self.reports / 'second.txt').exists())

    def test_failed_cleanup_keeps_failure_and_does_not_start_next_suite(self):
        def command(args, **kwargs):
            self.calls.append(args)
            if args[:3] == ['shell', 'am', 'force-stop']:
                raise RuntimeError('device disconnected')
            return successful()
        self.assertTrue(self.run_suites(command))
        self.assertEqual(sum(args[:3] == ['shell', 'am', 'instrument'] for args in self.calls), 1)
        self.assertTrue((self.reports / 'first-cleanup-error.txt').exists())


class WorkflowPlatformTest(unittest.TestCase):
    def test_selects_pinned_native_archive_or_rejects_unsupported_platform(self):
        spec = importlib.util.spec_from_file_location('workflow_check', Path(__file__).with_name('check-workflows.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for system, machine, expected in [('Darwin', 'arm64', 'darwin_arm64'),
                                           ('Darwin', 'x86_64', 'darwin_amd64'),
                                           ('Linux', 'x86_64', 'linux_amd64'),
                                           ('Linux', 'aarch64', 'linux_arm64')]:
            self.assertEqual(module.distribution(system, machine), expected)
            self.assertRegex(module.SHA256[expected], r'^[a-f0-9]{64}$')
        with self.assertRaises(ValueError):
            module.distribution('Windows', 'AMD64')


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'test.apk').write_bytes(b'apk')
        self.manifest = {'schemaVersion': 1, 'sha': 'abc', 'runId': '2', 'runAttempt': '1',
                         'files': [{'path': 'test.apk', 'sha256': hashlib.sha256(b'apk').hexdigest()}],
                         'suites': [{'name': name, 'package': 'test.app', 'testPackage': 'test.app',
                                     'runner': 'test.Runner', 'testApk': 'test.apk'}
                                    for name in ('app', 'library', 'consumer-minified', 'upgrade-debug', 'upgrade-release')]}
        self.addCleanup(self.temp.cleanup)

        for suite in self.manifest['suites']:
            if suite['name'].startswith('upgrade-'):
                suite.update(apk='test.apk', legacyApk='test.apk', legacyTestApk='test.apk')

    def check(self):
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        return verify(self.root, 'abc', '2', '1')

    def test_valid(self):
        self.check()

    def test_identity(self):
        self.manifest['sha'] = 'wrong'
        with self.assertRaises(ValueError):
            self.check()

    def test_corruption_missing_and_extra(self):
        (self.root / 'test.apk').write_bytes(b'wrong')
        with self.assertRaises(ValueError):
            self.check()
        (self.root / 'test.apk').unlink()
        with self.assertRaises(ValueError):
            self.check()
        (self.root / 'test.apk').write_bytes(b'apk')
        (self.root / 'extra').write_bytes(b'extra')
        with self.assertRaises(ValueError):
            self.check()

    def test_path_traversal(self):
        self.manifest['files'][0]['path'] = '../test.apk'
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_suite(self):
        self.manifest['suites'].pop()
        with self.assertRaises(ValueError):
            self.check()


class ReleasePayloadTest(unittest.TestCase):
    def test_fixed_release_payload(self):
        spec = importlib.util.spec_from_file_location('publish_central', Path(__file__).with_name('publish-central.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError):
                module.payload_files(root, '1.1.0-SNAPSHOT')
            with self.assertRaises(ValueError):
                module.payload_files(root, '1.1.0')
            target = root / 'io/github/logan0817/multiurlmanager/1.1.0'
            release_payload(target)
            (target / 'multiurlmanager-1.1.0.aar.sha1').write_text('old checksum')
            self.assertEqual(len(module.payload_files(root, '1.1.0')), 4)


class ReleaseSourceTest(unittest.TestCase):
    def test_only_successful_main_push_for_exact_sha(self):
        spec = importlib.util.spec_from_file_location('release_source', Path(__file__).with_name('release-source.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        valid = {'id': 4, 'head_branch': 'main', 'head_sha': 'abc', 'conclusion': 'success', 'event': 'push'}
        self.assertEqual(module.select_run([valid], 'abc'), valid)
        for key, value in (('head_branch', 'feature'), ('head_sha', 'wrong'), ('conclusion', 'skipped'), ('event', 'pull_request')):
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.select_run([{**valid, key: value}], 'abc')


class CentralRecoveryTest(unittest.TestCase):
    def test_recovery_checks_content_before_publish(self):
        spec = importlib.util.spec_from_file_location('publish_central', Path(__file__).with_name('publish-central.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            original = Path.cwd()
            os.chdir(temp)
            try:
                target = Path('build/ci/candidate/maven/io/github/logan0817/multiurlmanager/1.1.0')
                release_payload(target)
                environment = {'CANDIDATE_SHA': 'abc', 'CANDIDATE_RUN_ID': '1', 'CANDIDATE_ATTEMPT': '1',
                               'RELEASE_VERSION': '1.1.0', 'RELEASE_ACTION': 'publish',
                               'DEPLOYMENT_ID': '00000000-0000-0000-0000-000000000000'}
                calls = []
                def remote(path, **kwargs):
                    calls.append(path)
                    if path.startswith('status?'):
                        return json.dumps({'deploymentId': environment['DEPLOYMENT_ID'], 'deploymentState': 'VALIDATED',
                                           'purls': ['pkg:maven/io.github.logan0817/multiurlmanager@1.1.0']}).encode()
                    if '/download/' in path:
                        return b'tampered'
                    raise AssertionError('不能在内容不一致时发布')
                with patch.dict(os.environ, environment), patch.object(module, 'verify', return_value={'version': '1.1.0', 'event': 'push'}), patch.object(module, 'request', side_effect=remote):
                    with self.assertRaisesRegex(ValueError, '不一致'):
                        module.main()
                self.assertFalse(any(path == 'deployment/' + environment['DEPLOYMENT_ID'] for path in calls))
            finally:
                os.chdir(original)


class DocumentationTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('check_docs', Path(__file__).with_name('check-docs.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.page = Path(self.temp.name) / 'README.md'

    def check(self, source):
        self.page.write_text(source, encoding='utf-8')
        return self.module.check_markdown(self.page)

    def test_checks_html_images_and_markdown_links(self):
        self.assertTrue(self.check('<img src="missing.png" />\n'))
        self.assertTrue(self.check('[missing](missing.md)\n'))
        (self.page.parent / 'image.png').write_bytes(b'fixture')
        self.assertEqual([], self.check('<img src="image.png" />\n[External](https://example.com)\n'))

    def test_checks_json_xml_examples_and_fences(self):
        self.assertTrue(self.check('```json\n{"key":}\n```\n'))
        self.assertTrue(self.check('```xml\n<resources>\n```\n'))
        self.assertTrue(self.check('```kotlin\nval x = 1\n'))
        self.assertEqual([], self.check('```json\n{"key":1}\n```\n```xml\n<resources />\n```\n'))


if __name__ == '__main__':
    unittest.main()
