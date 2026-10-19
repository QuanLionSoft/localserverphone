"""Adversarial ZIP fixtures and rollback fault injection; no dependencies."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest import mock
import uuid
import zipfile


ASSET = Path(__file__).resolve().parents[2] / "app/src/main/assets/pocketpaas_deploy.py"
SPEC = importlib.util.spec_from_file_location("pocketpaas_deploy", ASSET)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class DeploymentWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("PPAAS_TEST_TMP"))
        self.addCleanup(self.temporary.cleanup)
        self.fixture = Path(self.temporary.name)
        self.root = self.fixture / "root"
        self.root.mkdir()
        self.projects = self.root / "htdocs"
        self.projects.mkdir()
        self.runtime_path = self.root / "tmp"
        (self.runtime_path / "uploads").mkdir(parents=True)
        self.deployment_id = str(uuid.uuid4())
        self.domain = "example.test"
        self.project_type = "STATIC"
        if os.name == "nt":
            original_resolve = runtime.Path.resolve
            fixture_root = original_resolve(self.fixture)

            def fixture_resolve(path, strict=False):
                try:
                    return original_resolve(path, strict=strict)
                except PermissionError:
                    if not strict or not path.exists():
                        raise
                    normalized = original_resolve(path)
                    normalized.relative_to(fixture_root)
                    for component in [normalized] + list(normalized.parents):
                        if component == fixture_root.parent:
                            break
                        runtime.reject_link(component.lstat())
                    return normalized

            resolver = mock.patch.object(runtime.Path, "resolve", fixture_resolve)
            resolver.start()
            self.addCleanup(resolver.stop)

    @property
    def archive(self):
        return self.runtime_path / "uploads" / (self.deployment_id + ".zip")

    @property
    def active(self):
        return self.projects / self.domain

    @property
    def staging(self):
        return self.projects / (".deploy-" + self.deployment_id)

    @property
    def backup(self):
        return self.projects / (".backup-" + self.deployment_id)

    def call(self, operation):
        return runtime.operate(str(self.root), str(self.projects), str(self.runtime_path), operation,
                               self.domain, self.project_type, None if operation == "inspect" else self.deployment_id)

    def journal_list(self):
        return runtime.operate(str(self.root), str(self.projects), str(self.runtime_path), "journals",
                               "recovery.invalid", "STATIC")["journals"]

    def test_recovery_enumerates_only_validated_journal_identity_and_state(self):
        self.zip([("index.html", "new")])
        self.call("prepare")
        self.assertEqual([{"deploymentId": self.deployment_id, "domain": self.domain,
                           "projectType": "STATIC", "state": "prepared"}], self.journal_list())
        self.call("activate")
        self.assertEqual("active", self.journal_list()[0]["state"])
        self.call("complete")
        self.assertEqual([], self.journal_list())

    def test_recovery_rejects_tampered_journal_without_modifying_project(self):
        self.zip([("index.html", "new")])
        self.call("prepare")
        path = self.runtime_path / "deployments" / (self.deployment_id + ".json")
        original = json.loads(path.read_text())
        for key, bad in (("deploymentId", str(uuid.uuid4())), ("domain", "../other"),
                         ("domain", None), ("projectType", "UNKNOWN"), ("state", "unknown")):
            changed = dict(original)
            changed[key] = bad
            path.write_text(json.dumps(changed))
            with self.assertRaises(runtime.DeploymentError):
                self.journal_list()
            self.assertTrue(self.staging.is_dir())
        path.write_text(json.dumps(original))
        self.call("abort")

    def test_recovery_rejects_unowned_journal_filename(self):
        directory = self.runtime_path / "deployments"
        directory.mkdir()
        (directory / "guessed.json").write_text("{}")
        with self.assertRaises((runtime.DeploymentError, ValueError)):
            self.journal_list()

    def test_recovery_ignores_uncommitted_atomic_journal_temporary_files(self):
        directory = self.runtime_path / "deployments"
        directory.mkdir()
        temporary = directory / (".journal-" + uuid.uuid4().hex)
        temporary.write_text("partial")
        self.assertEqual([], self.journal_list())
        self.assertEqual("partial", temporary.read_text())

    def zip(self, entries, compression=zipfile.ZIP_STORED):
        with zipfile.ZipFile(self.archive, "w", compression=compression) as archive:
            for name, content in entries:
                archive.writestr(name, content)

    def prepared(self, previous=None):
        if previous is not None:
            self.active.mkdir()
            (self.active / "index.html").write_text(previous)
        self.zip([("index.html", "new")])
        return self.call("prepare")

    def assert_rejected_zip(self, entries):
        self.zip(entries)
        with self.assertRaises((runtime.DeploymentError, OSError, zipfile.BadZipFile)):
            self.call("prepare")
        self.assertFalse(self.staging.exists())
        self.assertFalse(self.active.exists())

    def test_static_utf8_and_single_wrapper_extraction(self):
        self.zip([("folder/", ""), ("folder/index.html", "İstanbul 😀"), ("folder/assets/main.js", "ok")])
        prepared = self.call("prepare")
        self.assertEqual(self.active.as_posix(), prepared["metadata"]["rootDir"])
        self.assertFalse(self.active.exists())
        self.assertEqual("İstanbul 😀", (self.staging / "index.html").read_text(encoding="utf-8"))
        self.assertFalse((self.staging / "folder").exists())
        self.call("activate")
        self.call("complete")
        self.assertEqual("ok", (self.active / "assets/main.js").read_text())
        self.assertFalse(self.archive.exists())
        self.assertFalse(self.backup.exists())
        self.assertEqual("STATIC", self.call("inspect")["metadata"]["entryPoint"])

    def test_existing_project_survives_prepare_and_rollback(self):
        prepared = self.prepared("old")
        self.assertTrue(prepared["hadPreviousProject"])
        self.assertEqual("old", (self.active / "index.html").read_text())
        self.call("activate")
        self.assertEqual("new", (self.active / "index.html").read_text())
        self.assertEqual("old", (self.backup / "index.html").read_text())
        self.call("abort")
        self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())
        self.assertFalse(self.staging.exists())
        self.assertFalse(self.backup.exists())

    def test_abort_before_activation_never_removes_old_active(self):
        self.prepared("old")
        self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_abort_without_journal_never_deletes_active(self):
        self.active.mkdir()
        (self.active / "private.txt").write_text("owner")
        self.call("abort")
        self.assertEqual("owner", (self.active / "private.txt").read_text())

    def test_abort_new_deployment_removes_only_owned_active(self):
        unrelated = self.projects / "unrelated.test"
        unrelated.mkdir()
        (unrelated / "keep").write_text("owner")
        self.prepared()
        self.call("activate")
        self.call("abort")
        self.assertFalse(self.active.exists())
        self.assertEqual("owner", (unrelated / "keep").read_text())

    def test_activation_response_ambiguity_can_be_recovered(self):
        self.prepared("old")
        original = runtime.Workspace.write_journal

        def fail_final_write(workspace, record, state):
            if state == "active":
                raise OSError("response uncertain after directory rename")
            return original(workspace, record, state)

        with mock.patch.object(runtime.Workspace, "write_journal", fail_final_write):
            with self.assertRaises(OSError):
                self.call("activate")
        self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_activation_failure_before_backup_rename_keeps_old(self):
        self.prepared("old")
        original = runtime.Tree.rename

        def fail_first_swap(tree, src, dst, replace=False):
            if src[-1] == self.domain:
                raise OSError("rename failed")
            return original(tree, src, dst, replace)

        with mock.patch.object(runtime.Tree, "rename", fail_first_swap):
            with self.assertRaises(OSError):
                self.call("activate")
        self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_interrupted_abort_new_project_is_retried(self):
        self.prepared()
        self.call("activate")
        with mock.patch.object(runtime.Tree, "remove_directory", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                self.call("abort")
        self.call("abort")
        self.assertFalse(self.active.exists())

    def test_interrupted_abort_after_old_restore_is_retried(self):
        self.prepared("old")
        self.call("activate")
        original = runtime.Tree.rename

        def uncertain_restore(tree, src, dst, replace=False):
            result = original(tree, src, dst, replace)
            if src[-1].startswith(".backup-"):
                raise OSError("fsync or response uncertain")
            return result

        with mock.patch.object(runtime.Tree, "rename", uncertain_restore):
            with self.assertRaises(OSError):
                self.call("abort")
        self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_rolled_back_cleanup_debt_is_retried_without_touching_active(self):
        self.prepared("old")
        self.call("activate")
        original = runtime.Tree.unlink

        def fail_archive_delete(tree, parts):
            if parts[-1] == self.deployment_id + ".zip":
                raise OSError("archive cleanup interrupted")
            return original(tree, parts)

        with mock.patch.object(runtime.Tree, "unlink", fail_archive_delete):
            with self.assertRaises(OSError):
                self.call("abort")
        self.call("abort")
        self.assertFalse(self.archive.exists())
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_commit_point_rejects_rollback_but_cleanup_can_retry(self):
        self.prepared("old")
        self.call("activate")
        with mock.patch.object(runtime.Tree, "remove_directory", side_effect=OSError("cleanup interrupted")):
            with self.assertRaises(OSError):
                self.call("complete")
        with self.assertRaises(runtime.DeploymentError):
            self.call("abort")
        self.call("complete")
        self.call("complete")
        self.assertEqual("new", (self.active / "index.html").read_text())

    def test_prepare_missing_metadata_cleans_owned_staging(self):
        self.project_type = "NODEJS"
        self.zip([("index.js", "run")])
        with self.assertRaises(runtime.DeploymentError):
            self.call("prepare")
        self.assertFalse(self.staging.exists())
        self.assertFalse(self.active.exists())

    def test_node_metadata_checks_package_and_entrypoint(self):
        self.project_type = "NODEJS"
        self.zip([("index.js", "run"), ("package.json", '{"name":"app"}')])
        self.assertEqual("NODEJS", self.call("prepare")["metadata"]["entryPoint"])
        self.call("activate")
        self.assertTrue(self.call("inspect")["metadata"]["hasPackageJson"])

    def test_django_and_flask_requirements_and_entrypoints(self):
        self.project_type = "DJANGO"
        for entry, expected in (("manage.py", "DJANGO"), ("app.py", "FLASK")):
            with self.subTest(entry=entry):
                self.deployment_id = str(uuid.uuid4())
                self.zip([(entry, "pass"), ("requirements.txt", "")])
                self.assertEqual(expected, self.call("prepare")["metadata"]["entryPoint"])
                self.call("abort")

    def test_python_missing_requirements_is_rejected(self):
        self.project_type = "DJANGO"
        self.assert_rejected_zip([("manage.py", "pass")])

    def test_react_source_prepare_then_built_output_inspection(self):
        self.project_type = "REACT"
        self.zip([("package.json", '{"scripts":{"build":"vite build"}}'), ("src/main.js", "source")])
        self.call("prepare")
        self.call("activate")
        with self.assertRaises(runtime.DeploymentError):
            self.call("inspect")
        (self.active / "dist").mkdir()
        (self.active / "dist/index.html").write_text("built")
        self.assertEqual((self.active / "dist").as_posix(), self.call("inspect")["metadata"]["rootDir"])

    def test_invalid_package_json_is_rejected(self):
        self.project_type = "NODEJS"
        self.assert_rejected_zip([("index.js", "run"), ("package.json", "[]")])

    def test_traversal_absolute_backslash_and_controls_are_rejected(self):
        for name in ("../escape", "/escape", "C:/escape", "a\\b", "a/../b", "a//b", "a/./b", "a\nb", "a\x00b"):
            with self.subTest(name=name):
                info = zipfile.ZipInfo(name)
                with self.assertRaises(runtime.DeploymentError):
                    runtime.safe_name(info)

    def test_reserved_devices_trailing_dot_and_space_are_rejected(self):
        for name in ("CON", "nested/nul.txt", "com1.js", "folder.", "file "):
            with self.subTest(name=name):
                with self.assertRaises(runtime.DeploymentError):
                    runtime.safe_name(zipfile.ZipInfo(name))

    def test_duplicate_and_casefold_conflicts_are_rejected(self):
        for entries in ([('a.txt', 'one'), ('a.txt', 'two')],
                        [('a.txt', 'one'), ('A.txt', 'two')],
                        [('Foo/a', 'one'), ('foo/b', 'two')]):
            with self.subTest(entries=entries):
                self.assert_rejected_zip(entries)

    def test_file_directory_conflicts_are_rejected_in_both_orders(self):
        for entries in ([('a', 'file'), ('a/b', 'child')], [('a/b', 'child'), ('a', 'file')]):
            with self.subTest(entries=entries):
                self.assert_rejected_zip(entries)

    def test_symlink_special_modes_and_encryption_are_rejected(self):
        for mode in (stat.S_IFLNK, stat.S_IFIFO, stat.S_IFSOCK, stat.S_IFCHR, stat.S_IFBLK):
            with self.subTest(mode=mode):
                info = zipfile.ZipInfo("link")
                info.external_attr = (mode | 0o777) << 16
                with self.assertRaises(runtime.DeploymentError):
                    runtime.safe_name(info)
        info = zipfile.ZipInfo("encrypted")
        info.flag_bits = 1
        with self.assertRaises(runtime.DeploymentError):
            runtime.safe_name(info)

    def test_directory_payload_is_rejected(self):
        self.assert_rejected_zip([("directory/", "payload")])

    def test_compression_bomb_rejected_before_writing(self):
        self.zip([("bomb", b"0" * (1024 * 1024))], compression=zipfile.ZIP_DEFLATED)
        with self.assertRaises(runtime.DeploymentError):
            self.call("prepare")
        self.assertFalse(self.staging.exists())

    def test_unsupported_compression_is_rejected_before_staging_or_active_changes(self):
        self.active.mkdir()
        (self.active / "index.html").write_text("old")
        self.zip([("index.html", "new")], compression=zipfile.ZIP_BZIP2)
        with mock.patch.object(runtime.zipfile.ZipFile, "open", side_effect=AssertionError("Decompression must not start")):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        self.assertFalse(self.staging.exists())
        self.assertFalse(self.backup.exists())
        self.assertEqual("old", (self.active / "index.html").read_text())

    def test_entry_count_and_total_size_limits(self):
        self.zip([("a", "12"), ("b", "34")])
        with mock.patch.object(runtime, "MAX_ENTRIES", 1):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        with mock.patch.object(runtime, "MAX_EXTRACTED_BYTES", 3):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        self.assertFalse(self.staging.exists())

    def test_declared_excessive_entry_count_is_rejected_before_zipfile_allocation(self):
        self.zip([("index.html", "new")])
        content = bytearray(self.archive.read_bytes())
        eocd = content.rfind(b"PK\x05\x06")
        struct.pack_into("<2H", content, eocd + 8, runtime.MAX_ENTRIES + 1, runtime.MAX_ENTRIES + 1)
        self.archive.write_bytes(content)
        with mock.patch.object(runtime.zipfile, "ZipFile", side_effect=AssertionError("ZipInfo allocation must not happen")):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        self.assertFalse(self.staging.exists())

    def test_forged_small_count_with_many_actual_empty_entries_is_rejected_before_allocation(self):
        self.zip([("empty-%05d" % index, "") for index in range(runtime.MAX_ENTRIES + 1)])
        content = bytearray(self.archive.read_bytes())
        eocd = content.rfind(b"PK\x05\x06")
        struct.pack_into("<2H", content, eocd + 8, 1, 1)
        self.archive.write_bytes(content)
        with mock.patch.object(runtime.zipfile, "ZipFile", side_effect=AssertionError("ZipInfo allocation must not happen")):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        self.assertFalse(self.staging.exists())

    def test_eocd_zip64_multidisk_and_bad_offsets_are_rejected_before_allocation(self):
        self.zip([("index.html", "new")])
        original = self.archive.read_bytes()
        eocd = original.rfind(b"PK\x05\x06")
        for offset, encoding, value in ((4, "<H", 1), (6, "<H", 1), (8, "<H", 2),
                                        (10, "<H", 0xffff), (12, "<L", 0xffffffff),
                                        (16, "<L", 0xffffffff), (16, "<L", len(original)),
                                        (20, "<H", 1)):
            with self.subTest(offset=offset, value=value):
                content = bytearray(original)
                struct.pack_into(encoding, content, eocd + offset, value)
                self.archive.write_bytes(content)
                with mock.patch.object(runtime.zipfile, "ZipFile", side_effect=AssertionError("ZipInfo allocation must not happen")):
                    with self.assertRaises(runtime.DeploymentError):
                        self.call("prepare")

    def test_central_directory_byte_limit_is_checked_before_allocation(self):
        self.zip([("index.html", "new")])
        with mock.patch.object(runtime, "MAX_CENTRAL_BYTES", 1), mock.patch.object(
                runtime.zipfile, "ZipFile", side_effect=AssertionError("ZipInfo allocation must not happen")):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")

    def test_central_record_count_must_match_eocd_before_allocation(self):
        self.zip([("index.html", "new")])
        content = bytearray(self.archive.read_bytes())
        eocd = content.rfind(b"PK\x05\x06")
        struct.pack_into("<2H", content, eocd + 8, 2, 2)
        self.archive.write_bytes(content)
        with mock.patch.object(runtime.zipfile, "ZipFile", side_effect=AssertionError("ZipInfo allocation must not happen")):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")

    def test_archive_comment_is_validated_and_supported(self):
        self.zip([("index.html", "new")])
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.comment = b"signed by project owner" * 100
        self.call("prepare")
        self.assertEqual("new", (self.staging / "index.html").read_text())

    def test_archive_and_file_size_limits(self):
        self.zip([("file", "123456")])
        with mock.patch.object(runtime, "MAX_ARCHIVE_BYTES", 5):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")
        with mock.patch.object(runtime, "MAX_FILE_BYTES", 5):
            with self.assertRaises(runtime.DeploymentError):
                self.call("prepare")

    def test_empty_and_directory_only_archives_are_rejected(self):
        for entries in ([], [("directory/", "")]):
            with self.subTest(entries=entries):
                self.assert_rejected_zip(entries)

    def test_crc_corruption_cleans_staging(self):
        self.zip([("index.html", "unique content for crc")])
        content = bytearray(self.archive.read_bytes())
        offset = content.index(b"unique content for crc")
        content[offset] ^= 1
        self.archive.write_bytes(content)
        with self.assertRaises(zipfile.BadZipFile):
            self.call("prepare")
        self.assertFalse(self.staging.exists())

    def test_path_depth_and_filename_limits(self):
        for name in ("a/" * runtime.MAX_DEPTH + "leaf", "x" * 256):
            with self.subTest(name=name):
                with self.assertRaises(runtime.DeploymentError):
                    runtime.safe_name(zipfile.ZipInfo(name))

    def test_bad_domain_uuid_and_overlapping_layout_are_rejected(self):
        self.zip([("index.html", "ok")])
        for domain in ("../evil", "EXAMPLE.test", "a b", "a;quit"):
            with self.subTest(domain=domain):
                with self.assertRaises(runtime.DeploymentError):
                    runtime.operate(str(self.root), str(self.projects), str(self.runtime_path), "prepare", domain,
                                    "STATIC", self.deployment_id)
        with self.assertRaises(runtime.DeploymentError):
            runtime.operate(str(self.root), str(self.projects), str(self.projects / "tmp"), "prepare",
                            self.domain, "STATIC", self.deployment_id)
        with self.assertRaises(runtime.DeploymentError):
            runtime.operate(str(self.root), str(self.projects), str(self.runtime_path), "prepare",
                            self.domain, "STATIC", self.deployment_id.upper())

    def test_runtime_outside_root_is_rejected(self):
        with self.assertRaises(runtime.DeploymentError):
            runtime.operate(str(self.root), str(self.projects), str(self.fixture / "outside"), "inspect",
                            self.domain, "STATIC")

    def test_journal_domain_mismatch_never_deletes_previous_project(self):
        self.prepared("old")
        journal = self.runtime_path / "deployments" / (self.deployment_id + ".json")
        record = json.loads(journal.read_text())
        record["domain"] = "other.test"
        journal.write_text(json.dumps(record))
        with self.assertRaises(runtime.DeploymentError):
            self.call("abort")
        self.assertEqual("old", (self.active / "index.html").read_text())

    def symlink(self, path, target, directory=False):
        try:
            path.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError):
            self.skipTest("Host does not permit real symlink fixtures.")

    def test_active_symlink_is_rejected_before_archive_mutation(self):
        outside = self.fixture / "outside"
        outside.mkdir()
        (outside / "owner").write_text("owner")
        self.symlink(self.active, outside, directory=True)
        self.zip([("index.html", "new")])
        with self.assertRaises((runtime.DeploymentError, OSError)):
            self.call("prepare")
        self.assertEqual("owner", (outside / "owner").read_text())

    def test_archive_symlink_is_rejected(self):
        outside = self.fixture / "outside.zip"
        outside.write_bytes(b"owner")
        self.symlink(self.archive, outside)
        with self.assertRaises((runtime.DeploymentError, OSError)):
            self.call("prepare")

    def test_rollback_unlinks_dependency_symlink_without_following_it(self):
        self.prepared()
        self.call("activate")
        outside = self.fixture / "outside"
        outside.mkdir()
        (outside / "owner").write_text("owner")
        self.symlink(self.active / "dependency-link", outside, directory=True)
        self.call("abort")
        self.assertEqual("owner", (outside / "owner").read_text())

    @unittest.skipUnless(os.name == "posix", "POSIX descriptor permissions require a Linux/Termux host.")
    def test_extracted_files_are_private_and_setuid_is_removed(self):
        info = zipfile.ZipInfo("index.html")
        info.external_attr = (stat.S_IFREG | 0o6777) << 16
        self.zip([(info, "new")])
        self.call("prepare")
        self.assertEqual(0o600, stat.S_IMODE((self.staging / "index.html").stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(self.staging.stat().st_mode))

    def test_cli_json_success_and_sanitized_failure(self):
        self.prepared()
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = runtime.main([str(self.root), str(self.projects), str(self.runtime_path), "activate",
                                 self.domain, "STATIC", self.deployment_id])
        self.assertEqual(0, code)
        self.assertTrue(json.loads(stdout.getvalue())["ok"])
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = runtime.main([str(self.root), str(self.projects), str(self.runtime_path), "prepare",
                                 "../private-path", "STATIC", self.deployment_id])
        self.assertEqual(2, code)
        self.assertNotIn("private-path", stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
