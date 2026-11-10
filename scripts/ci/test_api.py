"""公开 ABI 检查自身的回归：默认构造与静态/实例差异都必须被识别。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check_api', Path(__file__).with_name('check-api.py'))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class ApiBaselineTest(unittest.TestCase):
    def test_kotlin_default_constructor_is_included_in_modern_baseline(self):
        source = '''public final class example.Value {
  public example.Value(java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker);
    descriptor: (Ljava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
  public static example.Value copy$default(example.Value, java.lang.String, int, java.lang.Object);
    descriptor: (Lexample/Value;Ljava/lang/String;ILjava/lang/Object;)Lexample/Value;
}'''
        legacy = api.parse_class(source, 'example.Value')
        modern = api.parse_class(source, 'example.Value', include_defaults=True)
        self.assertEqual(len(legacy['members']), 1)
        self.assertEqual(len(modern['members']), 2)
        self.assertEqual(modern['members'][0]['name'], '<init>')
        self.assertTrue(api.missing_api({'example.Value': modern}, {'example.Value': legacy}))

    def test_changed_descriptor_staticness_and_parent_are_incompatible(self):
        before = {'kind': 'class', 'supertypes': ['example.Parent'],
                  'members': [{'kind': 'method', 'name': 'call', 'descriptor': '()V', 'static': False}]}
        for after in ({**before, 'supertypes': []},
                      {**before, 'members': [{**before['members'][0], 'static': True}]},
                      {**before, 'members': [{**before['members'][0], 'descriptor': '(I)V'}]}):
            self.assertTrue(api.missing_api({'example.Value': before}, {'example.Value': after}))

    def test_corrupt_cached_release_is_rejected_before_inspection(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '1.1.0.aar'
            path.write_bytes(b'not an official artifact')
            with self.assertRaisesRegex(ValueError, '摘要不匹配'):
                api.verified_baseline(path, '1.1.0')
