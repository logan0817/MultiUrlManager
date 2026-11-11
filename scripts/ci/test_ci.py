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

from artifacts import create, publication_files, verify
from instrumentation import validate_output


def successful(count=1):
    return ''.join(f'INSTRUMENTATION_STATUS: numtests={count}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n' for _ in range(count)) + f'OK ({count} tests)\nINSTRUMENTATION_CODE: -1\n'


def release_payload(target, artifact="multiurlmanager", version="1.1.0"):
    target.mkdir(parents=True, exist_ok=True)
    for suffix in ('.aar', '.pom', '-sources.jar', '-javadoc.jar'):
        content = ('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                   f'<groupId>io.github.logan0817</groupId><artifactId>{artifact}</artifactId>'
                   f'<version>{version}</version></project>'.encode()) if suffix == '.pom' else b'candidate'
        (target / (f'{artifact}-{version}' + suffix)).write_bytes(content)


def snapshot_payload(target, artifact='multiurlmanager', timestamp='20261030.091951', number=2, version='1.2.0-SNAPSHOT'):
    resolved = f'{version.removesuffix("-SNAPSHOT")}-{timestamp}-{number}'
    release_payload(target, artifact, version)
    entries = []
    updated = timestamp.replace('.', '')
    for classifier, extension in (('', 'aar'), ('', 'pom'), ('sources', 'jar'), ('javadoc', 'jar')):
        suffix = f'-{classifier}' if classifier else ''
        source = target / f'{artifact}-{version}{suffix}.{extension}'
        source.rename(target / f'{artifact}-{resolved}{suffix}.{extension}')
        entries.append(f'<snapshotVersion><classifier>{classifier}</classifier><extension>{extension}</extension>'
                       f'<value>{resolved}</value><updated>{updated}</updated></snapshotVersion>')
    metadata = (f'<metadata><groupId>io.github.logan0817</groupId><artifactId>{artifact}</artifactId><version>{version}</version>'
                f'<versioning><lastUpdated>{updated}</lastUpdated><snapshot><timestamp>{timestamp}</timestamp>'
                f'<buildNumber>{number}</buildNumber></snapshot><snapshotVersions>{"".join(entries)}</snapshotVersions>'
                '</versioning></metadata>')
    path = target / 'maven-metadata.xml'
    path.write_text(metadata)
    return resolved


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

    def test_new_candidate_requires_both_upgrade_suites_and_publications(self):
        self.manifest['schemaVersion'] = 2
        self.manifest['suites'].append({**self.manifest['suites'][1], 'name': 'okhttp'})
        self.manifest['version'] = '1.2.0'
        with self.assertRaises(ValueError):
            self.check()
        for variant in ('debug', 'release'):
            self.manifest['suites'].append({**next(s for s in self.manifest['suites'] if s['name'] == 'upgrade-release'), 'name': f'upgrade11-{variant}'})
        with self.assertRaisesRegex(ValueError, 'Maven'):
            self.check()
        for artifact in ('multiurlmanager', 'multiurlmanager-okhttp'):
            directory = self.root / f'maven/io/github/logan0817/{artifact}/1.2.0'
            release_payload(directory, artifact, '1.2.0')
            for path in directory.iterdir():
                self.manifest['files'].append({'path': path.relative_to(self.root).as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        self.check()
        self.manifest['schemaVersion'] = 1
        with self.assertRaises(ValueError):
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


class CandidateCreationTest(unittest.TestCase):
    def test_new_manifest_uses_exact_current_publications_and_all_upgrade_apks(self):
        self.assert_candidate('1.2.0')

    def test_timestamped_snapshot_can_be_created_verified_and_rejects_wrong_round(self):
        self.assert_candidate('1.2.0-SNAPSHOT')

    def test_13_candidate_requires_12_upgrade_and_keeps_older_suites(self):
        self.assert_candidate('1.3.0')

    def test_13_snapshot_candidate_has_all_current_publications(self):
        self.assert_candidate('1.3.0-SNAPSHOT')

    def assert_candidate(self, version):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            candidate = root / 'candidate'
            base = [
                'app/build/outputs/apk/debug/app-debug.apk',
                'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
                'library-android/build/outputs/apk/androidTest/debug/library-android-debug-androidTest.apk',
                'library-okhttp/build/outputs/apk/androidTest/debug/library-okhttp-debug-androidTest.apk',
                'fixtures/consumer/app/build/outputs/apk/minified/app-minified.apk',
                'fixtures/consumer/app/build/outputs/apk/androidTest/minified/app-minified-androidTest.apk',
            ]
            packages = {}
            for path in base:
                packages[path] = 'test.app'
            modern = version.startswith('1.3.')
            baselines = [('', ('legacy', 'current')), ('11', ('legacy11', 'current11'))]
            if modern:
                baselines.append(('12', ('legacy12', 'current12')))
            for baseline, stages in baselines:
                for variant in ('debug', 'release'):
                    for stage in stages:
                        for test in (False, True):
                            folder = f'androidTest/{variant}' if test else variant
                            filename = f'app{baseline}-{variant}' + ('-androidTest' if test else '') + '.apk'
                            path = f'fixtures/upgrade/build/{stage}/app/outputs/apk/{folder}/{filename}'
                            packages[path] = f'com.logan.multiurlmanager.upgrade{baseline}.{variant}' + ('.test' if test else '')
            for path in packages:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(path.encode())
            for artifact in ('multiurlmanager', 'multiurlmanager-okhttp'):
                target = root / 'build/ci/maven/io/github/logan0817' / artifact / version
                if version.endswith('-SNAPSHOT'):
                    snapshot_payload(target, artifact, '20261030.084946', 1, version)
                    snapshot_payload(target, artifact, version=version)
                else:
                    release_payload(target, artifact, version)
            release_payload(root / 'build/ci/maven/io/github/logan0817/multiurlmanager/1.1.0')
            with patch.dict(os.environ, {'RELEASE_VERSION': version}), \
                    patch('artifacts.apk_info', side_effect=lambda aapt, path: packages[path.relative_to(root).as_posix()]):
                create(root, candidate, 'abc', '3', '1', 'aapt')
            manifest = verify(candidate, 'abc', '3', '1')
            self.assertEqual(manifest['schemaVersion'], 3 if modern else 2)
            self.assertEqual(len(manifest['suites']), 10 if modern else 8)
            self.assertEqual(len(list((candidate / 'apk').glob('*.apk'))), 30 if modern else 22)
            if modern:
                incomplete = {**manifest, 'suites': [suite for suite in manifest['suites'] if suite['name'] != 'upgrade12-release']}
                (candidate / 'manifest.json').write_text(json.dumps(incomplete))
                with self.assertRaisesRegex(ValueError, '套件'):
                    verify(candidate, 'abc', '3', '1')
                downgraded = {**manifest, 'schemaVersion': 2, 'suites': [suite for suite in manifest['suites'] if not suite['name'].startswith('upgrade12-')]}
                (candidate / 'manifest.json').write_text(json.dumps(downgraded))
                with self.assertRaisesRegex(ValueError, '1.3'):
                    verify(candidate, 'abc', '3', '1')
                (candidate / 'manifest.json').write_text(json.dumps(manifest))
            self.assertFalse(any('1.1.0' in entry['path'] for entry in manifest['files']))
            if version.endswith('-SNAPSHOT'):
                self.assertEqual(manifest['snapshotVersions'], {
                    artifact: version.removesuffix('-SNAPSHOT') + '-20261030.091951-2' for artifact in ('multiurlmanager', 'multiurlmanager-okhttp')})
                self.assertFalse(any('084946-1' in entry['path'] for entry in manifest['files']))
                manifest['snapshotVersions']['multiurlmanager'] = version.removesuffix('-SNAPSHOT') + '-20261030.084946-1'
                (candidate / 'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaisesRegex(ValueError, '轮次'):
                    verify(candidate, 'abc', '3', '1')
                manifest['snapshotVersions']['multiurlmanager'] = version.removesuffix('-SNAPSHOT') + '-20261030.091951-2'
                extra = candidate / 'maven/io/github/logan0817/multiurlmanager/1.1.0/old.aar'
                extra.parent.mkdir(parents=True)
                extra.write_bytes(b'old')
                manifest['files'].append({'path': extra.relative_to(candidate).as_posix(), 'sha256': hashlib.sha256(b'old').hexdigest()})
                (candidate / 'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaisesRegex(ValueError, '混入'):
                    verify(candidate, 'abc', '3', '1')


class SnapshotPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = '1.2.0-SNAPSHOT'
        self.old = snapshot_payload(self.root, timestamp='20261030.084946', number=1)
        self.latest = snapshot_payload(self.root)

    def select(self, sealed=False):
        return publication_files(self.root, 'multiurlmanager', self.version, sealed)

    def test_latest_snapshot_selection_preserves_real_names_and_metadata(self):
        files, resolved = self.select()
        self.assertEqual(resolved, self.latest)
        self.assertEqual(len(files), 5)
        self.assertTrue(all(self.old not in path.name for path in files))
        self.assertIn(self.root / 'maven-metadata.xml', files)
        sealed = self.root / 'sealed'
        sealed.mkdir()
        for file in files:
            (sealed / file.name).write_bytes(file.read_bytes())
        self.assertEqual(publication_files(sealed, 'multiurlmanager', self.version, True)[1], self.latest)
        (sealed / f'multiurlmanager-{self.old}.aar').write_bytes(b'old')
        with self.assertRaisesRegex(ValueError, '混入'):
            publication_files(sealed, 'multiurlmanager', self.version, True)

    def test_missing_latest_does_not_fall_back_to_complete_older_snapshot(self):
        (self.root / f'multiurlmanager-{self.latest}-sources.jar').unlink()
        with self.assertRaisesRegex(ValueError, '缺少'):
            self.select()

    def test_stale_or_mixed_metadata_is_rejected(self):
        metadata = self.root / 'maven-metadata.xml'
        current = metadata.read_text()
        metadata.write_text(current.replace('20261030.091951', '20261030.084946').replace('20261030091951', '20261030084946').replace('<buildNumber>2', '<buildNumber>1').replace('084946-2', '084946-1'))
        with self.assertRaisesRegex(ValueError, '陈旧'):
            self.select()
        metadata.write_text(current.replace(f'<value>{self.latest}</value>', f'<value>{self.old}</value>', 1))
        with self.assertRaisesRegex(ValueError, '混入'):
            self.select()

    def test_missing_duplicate_and_wrong_classifier_or_coordinates_are_rejected(self):
        metadata = self.root / 'maven-metadata.xml'
        current = metadata.read_text()
        invalid = (
            current.replace('<classifier>sources</classifier>', '<classifier>unknown</classifier>'),
            current.replace('<classifier>sources</classifier>', '<classifier>javadoc</classifier>'),
            current.replace('<artifactId>multiurlmanager</artifactId>', '<artifactId>foreign</artifactId>'),
            current.replace('<extension>aar</extension>', '<extension>jar</extension>'),
        )
        for value in invalid:
            metadata.write_text(value)
            with self.assertRaises(ValueError):
                self.select()

    def test_gradle_module_metadata_must_match_snapshot_coordinate(self):
        module = self.root / f'multiurlmanager-{self.latest}.module'
        metadata = self.root / 'maven-metadata.xml'
        content = metadata.read_text().replace('</snapshotVersions>',
            f'<snapshotVersion><extension>module</extension><value>{self.latest}</value>'
            '<updated>20261030091951</updated></snapshotVersion></snapshotVersions>')
        metadata.write_text(content)
        module.write_text(json.dumps({'component': {'group': 'io.github.logan0817', 'module': 'multiurlmanager', 'version': self.version}}))
        self.assertIn(module, self.select()[0])
        module.write_text(json.dumps({'component': {'group': 'io.github.logan0817', 'module': 'other', 'version': self.version}}))
        with self.assertRaisesRegex(ValueError, 'module metadata'):
            self.select()

    def test_pom_and_checksum_must_match_selected_snapshot(self):
        aar = self.root / f'multiurlmanager-{self.latest}.aar'
        checksum = self.root / (aar.name + '.sha256')
        checksum.write_text(hashlib.sha256(aar.read_bytes()).hexdigest())
        self.assertIn(checksum, self.select()[0])
        checksum.write_text('0' * 64)
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.select()
        checksum.unlink()
        pom = self.root / f'multiurlmanager-{self.latest}.pom'
        pom.write_text(pom.read_text().replace('1.2.0-SNAPSHOT', '1.1.0'))
        with self.assertRaisesRegex(ValueError, 'POM'):
            self.select()


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
