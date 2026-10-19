"""验证发布阶段边界与真实 GPG 签名；只用临时密钥和模拟 Central，不访问生产账户。"""
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from test_ci import release_payload

spec = importlib.util.spec_from_file_location('central', Path(__file__).with_name('publish-central.py'))
central = importlib.util.module_from_spec(spec)
spec.loader.exec_module(central)


class CentralStagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(os.chdir, Path.cwd())
        os.chdir(self.temp.name)
        self.target = Path('build/ci/candidate/maven/io/github/logan0817/multiurlmanager/1.1.0')
        release_payload(self.target)
        self.deployment = '00000000-0000-0000-0000-000000000000'
        self.environment = {'CANDIDATE_SHA': 'abc', 'CANDIDATE_RUN_ID': '1', 'CANDIDATE_ATTEMPT': '1',
                            'RELEASE_VERSION': '1.1.0', 'DEPLOYMENT_ID': self.deployment}
        self.calls = []
        self.state = 'VALIDATED'
        self.signature_error = None
        self.purls = ['pkg:maven/io.github.logan0817/multiurlmanager@1.1.0']

    def remote(self, path, *args, **kwargs):
        self.calls.append(path)
        if path.startswith('status?'):
            return json.dumps({'deploymentId': self.deployment, 'deploymentState': self.state,
                               'purls': self.purls}).encode()
        if '/download/' in path:
            return (self.target / path.rsplit('/', 1)[-1]).read_bytes()
        if path == 'deployment/' + self.deployment:
            self.state = 'PUBLISHED'
            return b''
        raise AssertionError('Unexpected Central request: ' + path)

    def run_stage(self, action):
        with patch.dict(os.environ, {**self.environment, 'RELEASE_ACTION': action}, clear=True), \
                patch.object(central, 'verify', return_value={'version': '1.1.0', 'event': 'push'}), \
                patch.object(central, 'request', side_effect=self.remote), \
                patch.object(central, 'verify_deployment_signatures', side_effect=self.signature_error), \
                patch.object(central.time, 'sleep'), \
                patch.object(central.urllib.request, 'urlopen', side_effect=lambda url, **kw:
                             io.BytesIO((self.target / url.rsplit('/', 1)[-1]).read_bytes())):
            central.main()

    def test_upload_stops_at_validated_without_publishing(self):
        self.run_stage('upload')
        self.assertNotIn('deployment/' + self.deployment, self.calls)
        self.assertEqual(self.state, 'VALIDATED')
        self.assertEqual(len([p for p in self.calls if '/download/' in p]), 4)

    def test_publish_requires_id_before_any_request(self):
        self.environment['DEPLOYMENT_ID'] = ''
        with self.assertRaisesRegex(ValueError, 'deployment ID'):
            self.run_stage('publish')
        self.assertEqual(self.calls, [])

    def test_upload_accepts_central_aar_coordinates_without_publishing(self):
        base = self.purls[0]
        for purls in ([base + '?type=aar', base], [base, base + '?type=aar'], [base + '?type=aar']):
            with self.subTest(purls=purls):
                self.purls = purls
                self.run_stage('upload')
                self.assertNotIn('deployment/' + self.deployment, self.calls)
                self.assertEqual(self.state, 'VALIDATED')

    def test_published_accepts_aar_coordinates_during_recovery(self):
        self.purls.insert(0, self.purls[0] + '?type=aar')
        self.state = 'PUBLISHED'
        self.run_stage('publish')
        self.assertNotIn('deployment/' + self.deployment, self.calls)
        self.assertTrue(Path('build/ci/release/public-verification.txt').is_file())

    def test_recovery_rejects_missing_duplicate_or_foreign_coordinates(self):
        base = self.purls[0]
        invalid = (None, [], base, [None], [base, base],
                   [base, base.replace('multiurlmanager', 'flowbus')],
                   [base, base.replace('@1.1.0', '@1.0.6')],
                   [base, base + '?type=jar'], [base, base + '?type=aar&classifier=sources'])
        for state in ('VALIDATED', 'PUBLISHED'):
            for purls in invalid:
                with self.subTest(state=state, purls=purls):
                    self.state = state
                    self.purls = purls
                    self.calls.clear()
                    with self.assertRaisesRegex(ValueError, 'ID 或坐标'):
                        self.run_stage('publish')
                    self.assertEqual(len(self.calls), 1)

    def test_publish_verifies_then_publishes_once_without_upload(self):
        self.run_stage('publish')
        self.assertEqual(self.calls.count('deployment/' + self.deployment), 1)
        self.assertFalse(any(p.startswith('upload?') for p in self.calls))
        self.assertTrue(Path('build/ci/release/public-verification.txt').is_file())

    def test_recovery_rejects_wrong_signature_before_publishing(self):
        self.signature_error = ValueError('签名身份与预期完整指纹不一致')
        with self.assertRaisesRegex(ValueError, '签名身份'):
            self.run_stage('publish')
        self.assertNotIn('deployment/' + self.deployment, self.calls)

    def test_wrong_deployment_id_rejected_even_if_already_published(self):
        self.state = 'PUBLISHED'
        self.environment['DEPLOYMENT_ID'] = '11111111-1111-1111-1111-111111111111'
        with self.assertRaisesRegex(ValueError, 'ID 或坐标'):
            self.run_stage('publish')
        self.assertEqual(len(self.calls), 1)

    def test_rejects_pom_with_wrong_coordinates_and_extra_payload(self):
        maven = Path('build/ci/candidate/maven')
        pom = self.target / 'multiurlmanager-1.1.0.pom'
        pom.write_bytes(pom.read_bytes().replace(b'<artifactId>multiurlmanager', b'<artifactId>other'))
        with self.assertRaisesRegex(ValueError, 'POM 坐标'):
            central.payload_files(maven, '1.1.0')
        release_payload(self.target)
        (self.target / 'unrelated.jar').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, '未知发布文件'):
            central.payload_files(maven, '1.1.0')


class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.home = Path(cls.temp.name) / 'gnupg'
        cls.home.mkdir(mode=0o700)
        cls.gpg = shutil.which('gpg')
        if not cls.gpg:
            raise RuntimeError('发布回归测试需要 gpg；不得跳过签名校验')
        cls.environment = dict(os.environ, GNUPGHOME=str(cls.home))
        for name in ('default', 'expected'):
            subprocess.run([cls.gpg, '--batch', '--pinentry-mode', 'loopback', '--passphrase', '',
                            '--quick-generate-key', f'{name} <{name}@example.invalid>', 'ed25519', 'sign', '1d'],
                           env=cls.environment, check=True, capture_output=True, timeout=30)
        listing = subprocess.check_output([cls.gpg, '--batch', '--with-colons', '--list-secret-keys'],
                                          env=cls.environment, text=True)
        cls.fingerprints = [line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:')]
        cls.export = subprocess.check_output([cls.gpg, '--batch', '--export-secret-keys'], env=cls.environment)
        # 回收的只是本测试临时密钥环的 agent，不触及用户现有 GPG 服务。
        if shutil.which('gpgconf'):
            cls.addClassCleanup(subprocess.run, ['gpgconf', '--homedir', str(cls.home), '--kill', 'gpg-agent'],
                                capture_output=True, timeout=10)

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.maven = Path(self.work.name) / 'maven'
        release_payload(self.maven / 'io/github/logan0817/multiurlmanager/1.1.0')
        self.files = central.payload_files(self.maven, '1.1.0')
        self.output = Path(self.work.name) / 'central.zip'
        self.env = {**self.environment, 'GPG_EXECUTABLE': self.gpg, 'SIGNING_FINGERPRINT': self.fingerprints[1]}

    def test_local_bundle_selects_expected_key_and_checks_every_checksum(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(central, 'request') as remote:
            central.create_bundle(self.files, self.maven, self.output, 'gpg')
            remote.assert_not_called()
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(len(archive.namelist()), 40)
            for file in self.files:
                name = file.relative_to(self.maven).as_posix()
                self.assertEqual(archive.read(name), file.read_bytes())
                signature = Path(self.work.name) / (file.name + '.asc')
                signature.write_bytes(archive.read(name + '.asc'))
                central.verify_signature(self.gpg, self.environment, signature, file, self.fingerprints[1])
                with self.assertRaisesRegex(ValueError, '身份'):
                    central.verify_signature(self.gpg, self.environment, signature, file, self.fingerprints[0])
                for suffix in ('', '.asc'):
                    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                        expected = hashlib.new(algorithm, archive.read(name + suffix)).hexdigest().encode()
                        self.assertEqual(archive.read(name + suffix + '.' + algorithm), expected)

    def test_ci_bundle_selects_expected_key_from_multiple_imported_keys(self):
        with patch.dict(os.environ, {**self.env, 'SIGNING_KEY_BASE64': base64.b64encode(self.export).decode(),
                                     'SIGNING_PASSWORD': ''}, clear=True):
            central.create_bundle(self.files, self.maven, self.output)
        with zipfile.ZipFile(self.output) as archive:
            file = self.files[0]
            signature = Path(self.work.name) / 'ci.asc'
            signature.write_bytes(archive.read(file.relative_to(self.maven).as_posix() + '.asc'))
            central.verify_signature(self.gpg, self.environment, signature, file, self.fingerprints[1])

    def test_recovery_checks_downloaded_signatures_against_expected_key(self):
        environment = {**self.env, 'SIGNING_KEY_BASE64': base64.b64encode(self.export).decode()}
        with patch.dict(os.environ, environment, clear=True):
            central.create_bundle(self.files, self.maven, self.output, 'gpg')
            with zipfile.ZipFile(self.output) as archive, patch.object(
                    central, 'request', side_effect=lambda path, **kw: archive.read(path.split('/download/', 1)[1])):
                central.verify_deployment_signatures(self.files, self.maven, 'deployment-id')
                with patch.dict(os.environ, {'SIGNING_FINGERPRINT': self.fingerprints[0]}):
                    with self.assertRaisesRegex(ValueError, '签名身份'):
                        central.verify_deployment_signatures(self.files, self.maven, 'deployment-id')

    def test_missing_short_or_unknown_key_does_not_create_bundle(self):
        for value in ('', 'DEADBEEF', '0' * 40):
            with self.subTest(value=value), patch.dict(os.environ, {**self.env, 'SIGNING_FINGERPRINT': value}, clear=True):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    central.create_bundle(self.files, self.maven, self.output, 'gpg')
                self.assertFalse(self.output.exists())

    def test_invalid_signature_is_rejected(self):
        signature = Path(self.work.name) / 'invalid.asc'
        signature.write_bytes(b'invalid')
        with self.assertRaises(subprocess.CalledProcessError):
            central.verify_signature(self.gpg, self.environment, signature, self.files[0], self.fingerprints[1])


if __name__ == '__main__':
    unittest.main()
