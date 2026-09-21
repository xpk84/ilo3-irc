#!/usr/bin/env python3
"""Offline packaging contracts; no installation, GUI, credentials or network.

Run: python3 tests/test-packaging.py -v
Python is a development dependency only. Synthetic Java prevents GUI launch even
when testing broken launchers; the real sources are exercised separately by --check.
"""
import os
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('JDK8', '/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home'))


class Packaging(unittest.TestCase):
    def setUp(self):
        scratch = Path.home() / '.hermes/cache/scratch'
        scratch.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix='ilo3-packaging-', dir=scratch)
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.repo = self.base / 'checkout with spaces'
        self.repo.mkdir()
        for name in ('ilo3-irc.sh', 'install-app.sh', 'app-icon.icns'):
            shutil.copy2(ROOT / name, self.repo / name)
        if (ROOT / 'scripts').exists():
            shutil.copytree(ROOT / 'scripts', self.repo / 'scripts')
        (self.repo / 'src').mkdir()
        (self.repo / 'src/ILO3IRC.java').write_text('''public class ILO3IRC {
 public static void main(String[] args) {
  System.out.println("APP_STARTED");
  for (String arg : args) System.out.println("ARG=" + arg);
 }
 static class Nested {}
}''')
        (self.repo / 'src/Helper.java').write_text('public class Helper {}')
        self.env = dict(os.environ, JDK8=str(JDK))
        self.app = self.base / 'Candidate.app'

    def run_script(self, script='ilo3-irc.sh', *args, env=None):
        path = Path(script)
        if not path.is_absolute():
            path = self.repo / path
        result = subprocess.run(['/bin/bash', str(path), *args], env=env or self.env,
                                cwd=self.base, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=90)
        return result

    def test_invalid_explicit_runtime_has_actionable_diagnostic(self):
        env = dict(self.env, JDK8=str(self.base / 'missing JDK'))
        result = self.run_script('ilo3-irc.sh', '--check', env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('JDK8', result.stdout)
        self.assertIn('missing JDK', result.stdout)
        self.assertFalse((self.repo / 'build').exists())

    def test_non_java8_runtime_is_rejected(self):
        fake = self.base / 'fake'
        (fake / 'bin').mkdir(parents=True)
        for name in ('java', 'javac'):
            program = fake / 'bin' / name
            program.write_text('#!/bin/sh\nprintf \'openjdk version "21.0.1"\\n\'\n')
            program.chmod(0o755)
        result = self.run_script('ilo3-irc.sh', '--check', env=dict(self.env, JDK8=str(fake)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Java 8', result.stdout)
        self.assertIn('version', result.stdout)

    def test_x86_runtime_rejected_on_arm_hardware_even_under_rosetta(self):
        arm = subprocess.run(['/usr/sbin/sysctl', '-n', 'hw.optional.arm64'],
                             capture_output=True, text=True).stdout.strip() == '1'
        intel = Path('/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home')
        if not arm or not intel.exists():
            self.skipTest('requires ARM hardware and installed Intel Java 8')
        result = self.run_script('ilo3-irc.sh', '--check', env=dict(self.env, JDK8=str(intel)))
        self.assertNotEqual(result.returncode, 0)
        self.assertRegex(result.stdout, r'(?i)(arch|arm64)')
        self.assertNotIn('APP_STARTED', result.stdout)

    def test_explicit_jdk_bundle_root_and_default_discovery(self):
        for root in (str(JDK.parents[1]), None):
            env = dict(self.env)
            if root:
                env['JDK8'] = root
            else:
                env.pop('JDK8', None)
            result = self.run_script('ilo3-irc.sh', '--check', env=env)
            self.assertEqual(result.returncode, 0, result.stdout)

    def test_source_requires_compiler(self):
        runtime = self.base / 'java-only'
        (runtime / 'bin').mkdir(parents=True)
        (runtime / 'bin/java').symlink_to(JDK / 'bin/java')
        result = self.run_script('ilo3-irc.sh', '--check', env=dict(self.env, JDK8=str(runtime)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('javac', result.stdout)

    def install_candidate(self):
        # Guard RED runs against the legacy installer ignoring --output and
        # overwriting the user's real /Applications prototype.
        self.assertIn('--output', (self.repo / 'install-app.sh').read_text(),
                      'installer has no sandbox output option; unsafe to execute')
        result = self.run_script('install-app.sh', '--output', str(self.app))
        self.assertEqual(result.returncode, 0, result.stdout)
        return self.app / 'Contents/MacOS/ilo3-console'

    def test_packaged_app_is_complete_and_independent_of_checkout(self):
        launcher = self.install_candidate()
        classes = self.app / 'Contents/Resources/classes'
        built = {p.relative_to(self.repo / 'build') for p in (self.repo / 'build').rglob('*.class')}
        bundled = {p.relative_to(classes) for p in classes.rglob('*.class')}
        self.assertEqual(built, bundled)
        self.assertIn(Path('Helper.class'), bundled)
        hidden = self.repo.with_name('checkout renamed away')
        self.repo.rename(hidden)
        runtime = self.base / 'runtime without compiler'
        (runtime / 'bin').mkdir(parents=True)
        (runtime / 'bin/java').symlink_to(JDK / 'bin/java')
        result = self.run_script(str(launcher), '--check', env=dict(self.env, JDK8=str(runtime)))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn('APP_STARTED', result.stdout)
        self.assertIn('Classes OK', result.stdout)
        if shutil.which('codesign'):
            verify = subprocess.run(['codesign', '--verify', '--deep', '--strict', str(self.app)],
                                    text=True, capture_output=True)
            self.assertEqual(verify.returncode, 0, verify.stderr)
        payload = classes / 'Helper.class'
        original = payload.read_bytes()
        payload.write_bytes(b'corrupted')
        result = self.run_script(str(launcher), '--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Corrupt bundled file', result.stdout)
        payload.write_bytes(original)
        payload.unlink()
        result = self.run_script(str(launcher), '--check')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Helper.class', result.stdout)

    def test_existing_app_refused_and_argument_validation(self):
        self.install_candidate()
        marker = self.app / 'KEEP'
        marker.write_text('do not overwrite')
        result = self.run_script('install-app.sh', '--output', str(self.app))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('exists', result.stdout)
        self.assertEqual(marker.read_text(), 'do not overwrite')
        for args in (('--output', 'relative.app'), ('--output',), ('--bad-option',)):
            result = self.run_script('install-app.sh', *args)
            self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_resources_are_built_and_packaged_with_clean_rebuild(self):
        resources = self.repo / 'resources/config'
        resources.mkdir(parents=True)
        (resources / 'example.txt').write_text('resource payload')
        result = self.run_script('ilo3-irc.sh', '--check')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue((self.repo / 'build/config/example.txt').is_file(), 'resource not copied into build')
        self.assertEqual((self.repo / 'build/config/example.txt').read_text(), 'resource payload')
        (self.repo / 'build/Stale.class').write_bytes(b'stale')
        (self.repo / 'src/Helper.java').unlink()
        (self.repo / 'src/NewHelper.java').write_text('public class NewHelper {}')
        self.install_candidate()
        classes = self.app / 'Contents/Resources/classes'
        self.assertFalse((classes / 'Stale.class').exists())
        self.assertFalse((classes / 'Helper.class').exists())
        self.assertTrue((classes / 'NewHelper.class').exists())
        self.assertEqual((classes / 'config/example.txt').read_text(), 'resource payload')

    def test_application_flags_are_forwarded_unchanged(self):
        for launcher in ('ilo3-irc.sh', str(self.install_candidate())):
            result = self.run_script(launcher, '--host', 'example.invalid', '--label', 'two words')
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn('APP_STARTED\nARG=--host\nARG=example.invalid\nARG=--label\nARG=two words', result.stdout)

    def test_mismatched_compiler_rejected(self):
        root = self.base / 'mismatched'
        (root / 'bin').mkdir(parents=True)
        (root / 'bin/java').symlink_to(JDK / 'bin/java')
        compiler = root / 'bin/javac'
        compiler.write_text('#!/bin/sh\nprintf "javac 21.0.1\\n"\n')
        compiler.chmod(0o755)
        result = self.run_script('ilo3-irc.sh', '--check', env=dict(self.env, JDK8=str(root)))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('javac version 1.8', result.stdout)

    def test_java_home_fallback(self):
        # Only hide the preferred Zulu path in the isolated fixture, then exercise
        # the real system java_home fallback without changing installed JDKs.
        path = self.repo / 'scripts/runtime.sh'
        path.write_text(path.read_text().replace(
            'candidate=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home',
            'candidate=/nonexistent/ilo3-test-jdk'))
        env = dict(self.env)
        env.pop('JDK8', None)
        result = self.run_script('ilo3-irc.sh', '--check', env=env)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_original_icon_is_reproducible_and_has_all_sizes(self):
        generator = ROOT / 'assets/generate_icon.py'
        self.assertTrue(generator.is_file(), 'original icon generator/source is missing')
        output = self.base / 'generated'
        result = subprocess.run([sys.executable, str(generator), '--output-dir', str(output)],
                                capture_output=True, text=True, timeout=90)
        self.assertEqual(result.returncode, 0, result.stderr)
        generated = (output / 'app-icon.icns').read_bytes()
        self.assertEqual(generated, (ROOT / 'app-icon.icns').read_bytes())
        self.assertEqual(generated[:4], b'icns')
        self.assertEqual(struct.unpack('>I', generated[4:8])[0], len(generated))
        offset, sizes = 8, set()
        while offset < len(generated):
            length = struct.unpack('>I', generated[offset + 4:offset + 8])[0]
            self.assertGreater(length, 8)
            png = generated[offset + 8:offset + length]
            self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
            width, height = struct.unpack('>II', png[16:24])
            self.assertEqual(width, height)
            sizes.add(width)
            offset += length
        self.assertEqual(offset, len(generated))
        self.assertEqual(sizes, {16, 32, 64, 128, 256, 512, 1024})
        svg = (ROOT / 'assets/original-console.svg').read_text()
        self.assertIn('Original geometric console', svg)
        self.assertNotIn('<image', svg)
        self.assertTrue((ROOT / 'assets/PROVENANCE.txt').is_file())

    def test_real_sources_package_without_checkout_or_gui(self):
        shutil.rmtree(self.repo / 'src')
        shutil.copytree(ROOT / 'src', self.repo / 'src')
        result = self.run_script('ilo3-irc.sh', '--check')
        self.assertEqual(result.returncode, 0, result.stdout)
        launcher = self.install_candidate()
        classes = self.app / 'Contents/Resources/classes'
        built = {p.relative_to(self.repo / 'build') for p in (self.repo / 'build').rglob('*.class')}
        bundled = {p.relative_to(classes) for p in classes.rglob('*.class')}
        self.assertEqual(built, bundled)
        self.assertTrue(bundled)
        self.repo.rename(self.repo.with_name('real checkout hidden'))
        result = self.run_script(str(launcher), '--check',
                                 env=dict(self.env, PATH='/usr/bin:/bin:/usr/sbin:/sbin'))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('Classes OK', result.stdout)

    def test_fresh_check_builds_all_sources_without_starting_app(self):
        self.assertFalse((self.repo / 'build').exists())
        result = self.run_script('ilo3-irc.sh', '--check')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn('APP_STARTED', result.stdout)
        self.assertIn('1.8', result.stdout)
        self.assertRegex(result.stdout, r'(arm64|aarch64|x86_64|amd64)')
        for name in ('ILO3IRC.class', 'ILO3IRC$Nested.class', 'Helper.class'):
            self.assertTrue((self.repo / 'build' / name).is_file(), name)


if __name__ == '__main__':
    unittest.main()
