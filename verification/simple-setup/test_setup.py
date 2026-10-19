"""Exercise the exact shipped setup body without installing any host packages.

Run with Python 3.9+ standard library: python -m unittest discover -s verification/simple-setup -v
POSIX shell checks use `sh` on PATH or POCKETPAAS_TEST_SH (Git for Windows works).
"""

import os
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[2]
SOURCE = PROJECT / "app/src/main/java/dev/pocketpaas/domain/setup/TermuxSetupInstructions.kt"
SOURCE_TEXT = SOURCE.read_text(encoding="utf-8")
BODY = textwrap.dedent(
    SOURCE_TEXT.split('private val script = """', 1)[1].split('""".trimIndent()', 1)[0].strip("\r\n")
).replace("${'$'}", "$")
COMMAND = "sh -c '" + BODY.replace("'", "'\\''") + "'"
SETTINGS = BODY.split("python - <<'POCKETPAAS_SETTINGS'\n", 1)[1].split("\nPOCKETPAAS_SETTINGS\n", 1)[0]
DEFINITIONS = SETTINGS.split("directory = Path.home() / '.termux'", 1)[0]
NAMESPACE = {}
exec(compile(DEFINITIONS, str(SOURCE), "exec"), NAMESPACE)
UPDATE = NAMESPACE["without_external_app_property"]
SH = os.environ.get("POCKETPAAS_TEST_SH") or shutil.which("sh")
if SH is None and os.name == "nt":
    candidate = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/sh.exe"
    SH = str(candidate) if candidate.is_file() else None


class PropertiesEditingTest(unittest.TestCase):
    def test_empty_settings_enable_external_commands(self):
        self.assertEqual("allow-external-apps = true\n", UPDATE(""))

    def test_repeated_run_is_content_idempotent(self):
        original = "# Preserve me\nuse-black-ui = true\nallow-external-apps=false\n"
        once = UPDATE(original)
        self.assertEqual(once, UPDATE(once))
        self.assertEqual("# Preserve me\nuse-black-ui = true\nallow-external-apps = true\n", once)

    def test_comments_and_every_duplicate_spelling_are_handled(self):
        original = (
            "# allow-external-apps = false\n"
            "! Comment ending in a backslash\\\n"
            "  allow-external-apps\t: false\n"
            "allow-external-apps false\n"
            "allow-external-apps\n"
            "allow\\u002dexternal-apps=false\n"
            "allow-external-\\\n  apps = false\n"
            "volume-keys=volume\n"
        )
        self.assertEqual(
            "# allow-external-apps = false\n! Comment ending in a backslash\\\n"
            "volume-keys=volume\nallow-external-apps = true\n", UPDATE(original),
        )

    def test_unrelated_continuations_preserve_their_physical_lines(self):
        original = "other = first\\\n  allow-external-apps=false\nallow-external-apps = false\n"
        self.assertEqual(
            "other = first\\\n  allow-external-apps=false\nallow-external-apps = true\n", UPDATE(original),
        )

    def test_dangling_continuation_cannot_absorb_the_new_setting(self):
        self.assertEqual("other = value\\\n\nallow-external-apps = true\n", UPDATE("other = value\\"))
        self.assertEqual("other = value\\\n\nallow-external-apps = true\n", UPDATE("other = value\\\n"))

    def test_crlf_comments_and_unterminated_lines_are_preserved(self):
        self.assertEqual(
            "# comment\r\nextra=value\r\nallow-external-apps = true\r\n",
            UPDATE("# comment\r\nallow-external-apps=false\r\nextra=value"),
        )

    def test_form_feed_is_property_whitespace_instead_of_a_new_line(self):
        self.assertEqual("other=a\fb\nallow-external-apps = true\n", UPDATE("\fallow-external-apps=false\nother=a\fb\n"))

    def test_escaped_delimiter_in_an_unrelated_key_remains_untouched(self):
        original = "allow-external-apps\\:other = false\n"
        self.assertEqual(original + "allow-external-apps = true\n", UPDATE(original))

    def test_malformed_unicode_key_fails_closed(self):
        with self.assertRaises(ValueError):
            UPDATE("broken\\uXY00=value\n")


class AtomicSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pocketpaas-setup-test-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.directory = self.home / ".termux"
        self.settings = self.directory / "termux.properties"
        self.environment = dict(os.environ, HOME=str(self.home), USERPROFILE=str(self.home), PYTHONIOENCODING="utf-8")

    def run_settings(self):
        return subprocess.run(
            [sys.executable, "-c", SETTINGS], env=self.environment,
            text=True, encoding="utf-8", capture_output=True, timeout=15,
        )

    def assert_no_temporary_files(self):
        self.assertEqual([], list(self.directory.glob(".pocketpaas-setup-*")))

    def test_new_file_created_and_repeated_run_preserves_configuration(self):
        result = self.run_settings()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"allow-external-apps = true\n", self.settings.read_bytes())
        self.settings.write_bytes(b"# test\r\nallow-external-apps=false\r\nextra=keep\r\n")
        result = self.run_settings()
        self.assertEqual(0, result.returncode, result.stderr)
        expected = self.settings.read_bytes()
        self.assertEqual(b"# test\r\nextra=keep\r\nallow-external-apps = true\r\n", expected)
        self.assertEqual(0, self.run_settings().returncode)
        self.assertEqual(expected, self.settings.read_bytes())
        if os.name != "nt":
            self.assertEqual(0o600, stat.S_IMODE(self.settings.stat().st_mode))
        self.assert_no_temporary_files()

    def test_directory_at_the_settings_path_is_rejected(self):
        self.settings.mkdir(parents=True)
        self.assertNotEqual(0, self.run_settings().returncode)
        self.assertTrue(self.settings.is_dir())
        self.assert_no_temporary_files()

    def test_non_utf8_settings_are_preserved_without_overwrite(self):
        self.directory.mkdir()
        original = b"invalid=\xff\n"
        self.settings.write_bytes(original)
        self.assertNotEqual(0, self.run_settings().returncode)
        self.assertEqual(original, self.settings.read_bytes())
        self.assert_no_temporary_files()

    def test_oversized_settings_are_preserved_without_overwrite(self):
        self.directory.mkdir()
        original = b"x" * (1024 * 1024 + 1)
        self.settings.write_bytes(original)
        self.assertNotEqual(0, self.run_settings().returncode)
        self.assertEqual(original, self.settings.read_bytes())
        self.assert_no_temporary_files()

    def test_symlink_settings_do_not_modify_the_target(self):
        self.directory.mkdir()
        outside = self.home / "outside.properties"
        outside.write_bytes(b"do-not-change=yes\n")
        try:
            self.settings.symlink_to(outside)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"Host cannot create symbolic links: {error}")
        self.assertNotEqual(0, self.run_settings().returncode)
        self.assertEqual(b"do-not-change=yes\n", outside.read_bytes())
        self.assertTrue(self.settings.is_symlink())
        self.assert_no_temporary_files()

    def test_symlink_termux_directory_is_rejected(self):
        outside = self.home / "outside"
        outside.mkdir()
        try:
            self.directory.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"Host cannot create symbolic links: {error}")
        self.assertNotEqual(0, self.run_settings().returncode)
        self.assertEqual([], list(outside.iterdir()))


@unittest.skipUnless(SH, "POSIX sh unavailable; set POCKETPAAS_TEST_SH")
class ShellPipelineTest(unittest.TestCase):
    package_calls = [
        "pkg update -y", "pkg upgrade -y", "pkg install -y python nginx nodejs cloudflared",
    ]
    smoke_calls = [
        "python --version", "nginx -v", "node --version", "npm --version", "cloudflared --version",
    ]

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pocketpaas-setup-shell-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.binary = self.home / "bin"
        self.binary.mkdir()
        self.shell = SH
        if Path(SH).name.lower().startswith("busybox"):
            self.shell = str(self.binary / ("sh.exe" if os.name == "nt" else "sh"))
            shutil.copyfile(SH, self.shell)
            Path(self.shell).chmod(0o700)
        self.log = self.home / "calls.log"
        self.command_file = self.home / "command.sh"
        # File input preserves UTF-8 on Windows shells whose argv uses the ANSI code page.
        self.command_file.write_text(COMMAND + "\n", encoding="utf-8", newline="\n")
        self.environment = dict(
            os.environ, HOME=str(self.home), USERPROFILE=str(self.home),
            PYTHONIOENCODING="utf-8", POCKETPAAS_SETUP_TEST_PYTHON=sys.executable,
            POCKETPAAS_SETUP_TEST_LOG=str(self.log),
        )
        self.environment["PATH"] = str(self.binary) + os.pathsep + os.environ.get("PATH", "")
        self.write_fake("pkg", 'printf "pkg %s\\n" "$*" >> "$POCKETPAAS_SETUP_TEST_LOG"\n'
                        'if [ "$1" = "${POCKETPAAS_SETUP_FAIL_AT:-none}" ]; then exit 23; fi\n')
        self.write_fake("python", 'printf "python %s\\n" "$*" >> "$POCKETPAAS_SETUP_TEST_LOG"\n'
                        'if [ "${POCKETPAAS_SETUP_FAIL_BINARY:-none}" = python ]; then\n'
                        '  printf "CANNOT LINK EXECUTABLE python: missing test symbol\\n" >&2\n'
                        '  exit 127\n'
                        'fi\n'
                        'exec "$POCKETPAAS_SETUP_TEST_PYTHON" "$@"\n')
        self.write_fake("termux-reload-settings", 'printf "reload\\n" >> "$POCKETPAAS_SETUP_TEST_LOG"\n'
                        'exit "${POCKETPAAS_SETUP_RELOAD_EXIT:-0}"\n')
        for binary in ("nginx", "node", "npm", "cloudflared"):
            link_error = (
                'CANNOT LINK EXECUTABLE nginx: cannot locate symbol SSL_set_quic_tls_cbs'
                if binary == "nginx" else f"CANNOT LINK EXECUTABLE {binary}: missing test symbol"
            )
            self.write_fake(binary, f'printf "{binary} %s\\n" "$*" >> "$POCKETPAAS_SETUP_TEST_LOG"\n'
                            f'if [ "${{POCKETPAAS_SETUP_FAIL_BINARY:-none}}" = {binary} ]; then\n'
                            f'  printf "{link_error}\\n" >&2\n'
                            '  exit 127\n'
                            'fi\n'
                            'exit 0\n')

    def write_fake(self, name, contents):
        path = self.binary / name
        path.write_text("#!/bin/sh\n" + contents, encoding="utf-8", newline="\n")
        path.chmod(0o700)

    def run_command(self):
        return subprocess.run(
            [self.shell, str(self.command_file)], env=self.environment, text=True,
            encoding="utf-8", capture_output=True, timeout=30,
        )

    def test_exact_shipped_command_parses_as_one_shell_invocation(self):
        result = subprocess.run([self.shell, "-n", str(self.command_file)], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('script.replace("\'", "\'\\\\\'\'")', SOURCE_TEXT)
        self.assertNotRegex(BODY, r"\b(?:curl|wget|sudo)\b")

    def test_success_upgrades_installs_runs_every_binary_then_edits_settings_and_reloads(self):
        result = self.run_command()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Kurulum tamamlandı.", result.stdout)
        self.assertEqual(
            self.package_calls + self.smoke_calls + ["python -", "reload"],
            self.log.read_text().splitlines(),
        )
        self.assertEqual(b"allow-external-apps = true\n", (self.home / ".termux/termux.properties").read_bytes())

    def test_update_failure_stops_before_upgrade_install_or_config_edit(self):
        self.environment["POCKETPAAS_SETUP_FAIL_AT"] = "update"
        result = self.run_command()
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("Kurulum tamamlanamadı.", result.stderr)
        self.assertNotIn("Kurulum tamamlandı.", result.stdout)
        self.assertEqual(["pkg update -y"], self.log.read_text().splitlines())
        self.assertFalse((self.home / ".termux").exists())

    def test_upgrade_failure_stops_before_install_smoke_checks_or_config_edit(self):
        self.environment["POCKETPAAS_SETUP_FAIL_AT"] = "upgrade"
        result = self.run_command()
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("Kurulum tamamlanamadı.", result.stderr)
        self.assertNotIn("Kurulum tamamlandı.", result.stdout)
        self.assertEqual(self.package_calls[:2], self.log.read_text().splitlines())
        self.assertFalse((self.home / ".termux").exists())

    def test_install_failure_stops_before_config_edit_or_reload(self):
        self.environment["POCKETPAAS_SETUP_FAIL_AT"] = "install"
        result = self.run_command()
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("Kurulum tamamlanamadı.", result.stderr)
        self.assertEqual(
            self.package_calls,
            self.log.read_text().splitlines(),
        )
        self.assertFalse((self.home / ".termux").exists())

    def test_present_nginx_with_the_reported_ssl_link_error_never_reports_success(self):
        self.environment["POCKETPAAS_SETUP_FAIL_BINARY"] = "nginx"
        result = self.run_command()
        self.assertEqual(127, result.returncode, result.stderr)
        self.assertIn("SSL_set_quic_tls_cbs", result.stderr)
        self.assertIn("Kurulum tamamlanamadı.", result.stderr)
        self.assertNotIn("Kurulum tamamlandı.", result.stdout)
        self.assertEqual(self.package_calls + self.smoke_calls[:2], self.log.read_text().splitlines())
        self.assertFalse((self.home / ".termux").exists())

    def test_each_required_runtime_must_actually_execute_before_configuration(self):
        for index, binary in enumerate(("python", "nginx", "node", "npm", "cloudflared")):
            with self.subTest(binary=binary):
                self.log.unlink(missing_ok=True)
                self.environment["POCKETPAAS_SETUP_FAIL_BINARY"] = binary
                result = self.run_command()
                self.assertEqual(127, result.returncode, result.stderr)
                self.assertIn(f"CANNOT LINK EXECUTABLE {binary}", result.stderr)
                self.assertIn("Kurulum tamamlanamadı.", result.stderr)
                self.assertNotIn("Kurulum tamamlandı.", result.stdout)
                self.assertEqual(
                    self.package_calls + self.smoke_calls[:index + 1],
                    self.log.read_text().splitlines(),
                )
                self.assertFalse((self.home / ".termux").exists())

    def test_reload_failure_reports_failure_even_when_settings_were_updated(self):
        self.environment["POCKETPAAS_SETUP_RELOAD_EXIT"] = "17"
        result = self.run_command()
        self.assertEqual(17, result.returncode, result.stderr)
        self.assertIn("Kurulum tamamlanamadı.", result.stderr)
        self.assertNotIn("Kurulum tamamlandı.", result.stdout)
        self.assertTrue((self.home / ".termux/termux.properties").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
