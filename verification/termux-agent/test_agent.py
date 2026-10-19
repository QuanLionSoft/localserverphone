"""Real HTTP/process verification; run with Python 3 and no third-party modules."""

import base64
import http.client
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from unittest import mock


ASSET = Path(__file__).resolve().parents[2] / "app/src/main/assets/pocketpaas_agent.py"
SPEC = importlib.util.spec_from_file_location("pocketpaas_agent", ASSET)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)
TOKEN = "7" * 64


class AgentVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("PPAAS_TEST_TMP"))
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "root"
        self.prefix = self.directory / "prefix"
        (self.prefix / "bin").mkdir(parents=True)
        self.agent = runtime.Agent(TOKEN, root=self.root, prefix=self.prefix)
        if os.name == "nt":
            # Windows AppContainer denies GetFinalPathNameByHandle even in writable
            # fixtures. Keep the production strict resolver unchanged; only adapt
            # existing, non-reparse fixture paths for host-side HTTP verification.
            original_resolve = runtime.Path.resolve
            fixture_root = original_resolve(self.directory)

            def fixture_resolve(path, strict=False):
                try:
                    return original_resolve(path, strict=strict)
                except PermissionError:
                    if not strict or not path.exists():
                        raise
                    normalized = original_resolve(path)
                    try:
                        normalized.relative_to(fixture_root)
                    except ValueError:
                        raise PermissionError("Strict resolution is unavailable outside this fixture.")
                    for component in [normalized] + list(normalized.parents):
                        if component == fixture_root.parent:
                            break
                        if component.is_symlink() or (hasattr(component, "is_junction") and component.is_junction()):
                            raise PermissionError("Reparse paths require strict host resolution.")
                    return normalized

            resolver_patch = mock.patch.object(runtime.Path, "resolve", fixture_resolve)
            resolver_patch.start()
            self.addCleanup(resolver_patch.stop)
        self.server = runtime.LoopbackHTTPServer(0, self.agent)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.server_thread.start()

    def tearDown(self):
        self.agent.shutdown()
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, method, path, body=None, token=TOKEN, raw=None, extra_headers=None):
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            raw = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        try:
            connection.request(method, path, body=raw, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
        finally:
            connection.close()

    def spawn(self, program, environment=None, cwd=None):
        status, response = self.request("POST", "/processes", {
            "command": {"kind": "argv", "value": [sys.executable, "-c", program]},
            "env": environment or {}, "cwd": str(cwd or self.root),
        })
        self.assertEqual(201, status, response)
        self.assertIsInstance(response["pid"], int)
        return response["id"]

    def collect(self, identifier, timeout=15):
        deadline = time.monotonic() + timeout
        stdout = bytearray()
        stderr = bytearray()
        last = None
        while time.monotonic() < deadline:
            status, last = self.request("GET", "/processes/" + identifier + "/poll?waitMs=100")
            self.assertEqual(200, status, last)
            out = base64.b64decode(last["stdout"], validate=True)
            err = base64.b64decode(last["stderr"], validate=True)
            self.assertLessEqual(len(out), runtime.POLL_CHUNK_BYTES)
            self.assertLessEqual(len(err), runtime.POLL_CHUNK_BYTES)
            stdout.extend(out)
            stderr.extend(err)
            if last["stdoutClosed"] and last["stderrClosed"] and last["exitCode"] is not None:
                return bytes(stdout), bytes(stderr), last["exitCode"]
        self.fail("The process streams did not finish before timeout: " + repr(last))

    def test_runtime_reset_reaps_owned_children_and_allows_a_new_session(self):
        identifier = self.spawn("import time;time.sleep(60)")
        record = self.agent.record(identifier)
        pid = record.pid
        status, capability = self.request("GET", "/capabilities")
        self.assertEqual(1, capability["runtimeResetVersion"])
        status, result = self.request("POST", "/runtime/reset", {"persistedPids": [pid]})
        self.assertEqual(200, status, result)
        if os.name == "nt" and result.get("unconfirmedPids"):
            # Windows can expose a process exit code before its kernel handle becomes
            # signaled. The conservative production probe correctly refuses absence
            # in that interval. Wait read-only on this fixture's original owned handle,
            # then retry recovery; never suppress an unexpected or unowned PID.
            self.assertEqual({"stopped": True, "liveProcesses": 0, "unconfirmedPids": [pid]}, result)
            import ctypes
            from ctypes import wintypes
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            kernel.WaitForSingleObject.restype = wintypes.DWORD
            self.assertEqual(0, kernel.WaitForSingleObject(wintypes.HANDLE(int(record.process._handle)), 3000))
            status, result = self.request("POST", "/runtime/reset", {"persistedPids": [pid]})
            self.assertEqual(200, status, result)
        self.assertEqual({"stopped": True, "liveProcesses": 0, "unconfirmedPids": []}, result)
        self.assertEqual([], self.agent.statuses()["processes"])
        self.assertFalse(runtime._pid_exists(pid))
        identifier = self.spawn("print('new session')")
        self.assertEqual(b"new session\n", self.collect(identifier)[0].replace(b"\r\n", b"\n"))

    def test_runtime_reset_never_signals_an_unowned_live_pid(self):
        child = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"])
        try:
            status, result = self.request("POST", "/runtime/reset", {"persistedPids": [child.pid]})
            self.assertEqual(200, status, result)
            self.assertEqual([child.pid], result["unconfirmedPids"])
            self.assertIsNone(child.poll())
        finally:
            child.kill()
            child.wait(timeout=3)

    def test_runtime_reset_rejects_missing_authorization_and_invalid_pid_lists(self):
        status, _ = self.request("POST", "/runtime/reset", {"persistedPids": []}, token=None)
        self.assertEqual(401, status)
        for value in (None, [True], [0], [-1], [1, 1], [{}], list(range(1, 66))):
            status, result = self.request("POST", "/runtime/reset", {"persistedPids": value})
            self.assertEqual(400, status, result)

    def test_runtime_reset_refuses_in_progress_archive_operations(self):
        self.agent._archive_lock.acquire()
        try:
            status, result = self.request("POST", "/runtime/reset", {"persistedPids": []})
            self.assertEqual(409, status, result)
            self.assertFalse(self.agent._resetting)
        finally:
            self.agent._archive_lock.release()

    def test_runtime_reset_maintenance_gate_rejects_concurrent_process_creation(self):
        identifier = self.spawn("import time;time.sleep(60)")
        record = self.agent.record(identifier)
        entered = threading.Event()
        released = threading.Event()
        original = record.stop

        def paused_stop(grace):
            entered.set()
            self.assertTrue(released.wait(3))
            return original(grace)

        result = []
        with mock.patch.object(record, "stop", paused_stop):
            reset = threading.Thread(target=lambda: result.append(self.request("POST", "/runtime/reset", {"persistedPids": [record.pid]})))
            reset.start()
            try:
                self.assertTrue(entered.wait(3))
                status, response = self.request("POST", "/processes", {
                    "command": {"kind": "argv", "value": [sys.executable, "-c", "print('blocked')"]},
                    "env": {}, "cwd": str(self.root),
                })
                self.assertEqual(503, status, response)
            finally:
                released.set()
                reset.join(timeout=5)
        self.assertFalse(reset.is_alive())
        self.assertEqual(200, result[0][0], result)

    def test_runtime_reset_stop_failure_does_not_claim_success_or_forget_ownership(self):
        identifier = self.spawn("import time;time.sleep(60)")
        record = self.agent.record(identifier)
        with mock.patch.object(record, "stop", side_effect=runtime.ApiError(503, "test_stop", "test")):
            with mock.patch.object(record, "_group_alive", return_value=False):
                status, result = self.request("POST", "/runtime/reset", {"persistedPids": [record.pid]})
        self.assertEqual(503, status, result)
        self.assertIs(self.agent.record(identifier), record)
        self.assertIsNone(record.process.poll())
        self.assertFalse(self.agent._resetting)

    def test_health_requires_authentication(self):
        self.assertEqual((200, {"protocolVersion": 1}), self.request("GET", "/health"))
        for supplied in (None, "0" * 64, "wrong", "é"):
            status, body = self.request("GET", "/health", token=supplied)
            self.assertEqual(401, status)
            self.assertEqual("unauthorized", body["code"])

    def upload(self, identifier, data, headers=None, token=TOKEN):
        return self.request("PUT", "/archives/" + identifier, raw=data, token=token,
                            extra_headers={"Content-Type": "application/zip", **(headers or {})})

    def test_archive_streaming_round_trip_and_idempotent_delete(self):
        identifier = str(uuid.uuid4())
        data = bytes(range(256)) * 8192
        status, response = self.upload(identifier, data)
        self.assertEqual(201, status, response)
        destination = self.agent.runtime / "uploads" / (identifier + ".zip")
        self.assertEqual(str(destination), response["path"])
        self.assertEqual(len(data), response["bytes"])
        self.assertEqual(data, destination.read_bytes())
        if os.name == "posix":
            self.assertEqual(0o600, destination.stat().st_mode & 0o777)
        self.assertEqual([destination.name], [path.name for path in destination.parent.iterdir()])
        for _ in range(2):
            self.assertEqual((200, {"deleted": True}), self.request("DELETE", "/archives/" + identifier))

    def test_archive_endpoints_require_authentication_before_creating_files(self):
        identifier = str(uuid.uuid4())
        self.assertEqual(401, self.upload(identifier, b"PK", token=None)[0])
        self.assertEqual(401, self.request("DELETE", "/archives/" + identifier, token=None)[0])
        self.assertEqual(401, self.request("GET", "/capabilities", token=None)[0])
        self.assertFalse((self.agent.runtime / "uploads").exists())

    def test_archive_capabilities_do_not_change_process_protocol(self):
        status, body = self.request("GET", "/capabilities")
        self.assertEqual(200, status)
        self.assertEqual(1, body["archiveTransferVersion"])
        self.assertEqual(1, body["cleanupOwnershipVersion"])
        self.assertEqual(64 * 1024 * 1024, body["maximumArchiveBytes"])
        self.assertEqual((200, {"protocolVersion": 1}), self.request("GET", "/health"))

    def test_archive_rejects_invalid_ids_and_traversal(self):
        for identifier in ("a", "../outside", "%2e%2e%2foutside", "12345678-1234-1234-1234-123456789ab"):
            self.assertIn(self.upload(identifier, b"PK")[0], (400, 404))
            self.assertIn(self.request("DELETE", "/archives/" + identifier)[0], (400, 404))
        self.assertFalse((self.agent.runtime / "uploads").exists())

    def test_archive_rejects_size_content_type_and_chunked_requests(self):
        identifier = str(uuid.uuid4())
        self.assertEqual(413, self.upload(identifier, b"")[0])
        self.assertEqual(413, self.upload(identifier, b"", {"Content-Length": str(runtime.MAX_ARCHIVE_BYTES + 1)})[0])
        self.assertEqual(415, self.upload(identifier, b"PK", {"Content-Type": "text/plain"})[0])
        self.assertEqual(400, self.upload(identifier, b"PK", {"Transfer-Encoding": "chunked"})[0])
        self.assertFalse((self.agent.runtime / "uploads").exists())

    def test_archive_rejects_missing_and_duplicate_content_lengths(self):
        identifier = str(uuid.uuid4())
        for lengths, expected in (([], 411), (["2", "2"], 400), (["-1"], 400)):
            connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
            try:
                connection.putrequest("PUT", "/archives/" + identifier)
                connection.putheader("Authorization", "Bearer " + TOKEN)
                connection.putheader("Content-Type", "application/zip")
                for length in lengths:
                    connection.putheader("Content-Length", length)
                connection.endheaders()
                response = connection.getresponse()
                self.assertEqual(expected, response.status, response.read())
            finally:
                connection.close()
        self.assertFalse((self.agent.runtime / "uploads").exists())

    def test_archive_duplicate_id_never_overwrites_an_existing_upload(self):
        identifier = str(uuid.uuid4())
        self.assertEqual(201, self.upload(identifier, b"original")[0])
        self.assertEqual(409, self.upload(identifier, b"replacement")[0])
        self.assertEqual(b"original", (self.agent.runtime / "uploads" / (identifier + ".zip")).read_bytes())

    def test_archive_commit_does_not_require_hard_link_support(self):
        identifier = str(uuid.uuid4())
        # Termux compiles CPython without link/linkat; a hard-link dependency must fail this test.
        with mock.patch.object(runtime.os, "link", None, create=True):
            status, response = self.upload(identifier, b"without-hard-links")
        self.assertEqual(201, status, response)
        self.assertEqual(b"without-hard-links", Path(response["path"]).read_bytes())

    def test_archive_target_introduced_during_upload_is_preserved(self):
        import io
        identifier = str(uuid.uuid4())
        destination = self.agent.runtime / "uploads" / (identifier + ".zip")

        class IntroducesTarget(io.BytesIO):
            def read1(self, length):
                destination.write_bytes(b"existing-owner-file")
                return super().read1(length)

        with self.assertRaises(runtime.ApiError) as raised:
            self.agent.upload_archive(identifier, IntroducesTarget(b"replacement"), len(b"replacement"))
        self.assertEqual(409, raised.exception.status)
        self.assertEqual(b"existing-owner-file", destination.read_bytes())
        self.assertEqual([destination.name], [path.name for path in destination.parent.iterdir()])

    def test_uncertain_upload_cleanup_requires_the_committing_attempt_nonce(self):
        identifier = str(uuid.uuid4())
        first = str(uuid.uuid4())
        conflicting = str(uuid.uuid4())
        self.assertEqual(201, self.upload(identifier, b"original", {"X-PocketPaaS-Upload-Id": first})[0])
        self.assertEqual(409, self.upload(identifier, b"replacement", {"X-PocketPaaS-Upload-Id": conflicting})[0])
        status, body = self.request("DELETE", "/archives/" + identifier,
                                    extra_headers={"X-PocketPaaS-Upload-Id": conflicting})
        self.assertEqual(409, status, body)
        destination = self.agent.runtime / "uploads" / (identifier + ".zip")
        self.assertEqual(b"original", destination.read_bytes())
        self.assertEqual(200, self.request("DELETE", "/archives/" + identifier,
                                          extra_headers={"X-PocketPaaS-Upload-Id": first})[0])
        self.assertFalse(destination.exists())

    def test_archive_ownership_map_prunes_externally_removed_files(self):
        import io
        for _ in range(runtime.MAX_ARCHIVES * 2):
            identifier = str(uuid.uuid4())
            result = self.agent.upload_archive(identifier, io.BytesIO(b"PK"), 2)
            Path(result["path"]).unlink()
            self.assertLessEqual(len(self.agent._archive_owners), 1)
        self.agent.delete_archive(identifier)
        self.assertEqual({}, self.agent._archive_owners)

    def test_archive_upload_nonce_header_rejects_malformed_and_duplicate_values(self):
        identifier = str(uuid.uuid4())
        self.assertEqual(400, self.upload(identifier, b"PK", {"X-PocketPaaS-Upload-Id": "malformed"})[0])
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.putrequest("PUT", "/archives/" + identifier)
            connection.putheader("Authorization", "Bearer " + TOKEN)
            connection.putheader("Content-Type", "application/zip")
            connection.putheader("Content-Length", "2")
            connection.putheader("X-PocketPaaS-Upload-Id", str(uuid.uuid4()))
            connection.putheader("X-PocketPaaS-Upload-Id", str(uuid.uuid4()))
            connection.endheaders()
            connection.send(b"PK")
            response = connection.getresponse()
            self.assertEqual(400, response.status, response.read())
        finally:
            connection.close()
        self.assertFalse((self.agent.runtime / "uploads").exists())

    def test_archive_count_is_bounded_and_deletion_releases_capacity(self):
        identifiers = [str(uuid.uuid4()) for _ in range(runtime.MAX_ARCHIVES)]
        for identifier in identifiers:
            self.assertEqual(201, self.upload(identifier, b"PK")[0])
        extra = str(uuid.uuid4())
        self.assertEqual(429, self.upload(extra, b"PK")[0])
        self.assertEqual(200, self.request("DELETE", "/archives/" + identifiers[0])[0])
        self.assertEqual(201, self.upload(extra, b"PK")[0])

    def test_archive_truncated_request_cleans_partial_file(self):
        identifier = str(uuid.uuid4())
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.putrequest("PUT", "/archives/" + identifier)
            connection.putheader("Authorization", "Bearer " + TOKEN)
            connection.putheader("Content-Type", "application/zip")
            connection.putheader("Content-Length", "10000")
            connection.endheaders()
            connection.send(b"partial")
            connection.sock.shutdown(__import__("socket").SHUT_WR)
            response = connection.getresponse()
            self.assertEqual(400, response.status, response.read())
        finally:
            connection.close()
        self.assertEqual([], list((self.agent.runtime / "uploads").iterdir()))

    def test_slow_archive_upload_does_not_block_process_polling(self):
        identifier = str(uuid.uuid4())
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.putrequest("PUT", "/archives/" + identifier)
            connection.putheader("Authorization", "Bearer " + TOKEN)
            connection.putheader("Content-Type", "application/zip")
            connection.putheader("Content-Length", "10000")
            connection.endheaders()
            connection.send(b"P")
            process = self.spawn("print('during-upload')")
            stdout, stderr, exit_code = self.collect(process)
            self.assertEqual((b"during-upload\n", b"", 0), (stdout.replace(b"\r\n", b"\n"), stderr, exit_code))
            connection.send(b"K" * 9999)
            response = connection.getresponse()
            self.assertEqual(201, response.status, response.read())
        finally:
            connection.close()

    def test_concurrent_archive_upload_is_rejected_without_queuing_handler_threads(self):
        first = str(uuid.uuid4())
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.putrequest("PUT", "/archives/" + first)
            connection.putheader("Authorization", "Bearer " + TOKEN)
            connection.putheader("Content-Type", "application/zip")
            connection.putheader("Content-Length", "2")
            connection.endheaders()
            connection.send(b"P")
            deadline = time.monotonic() + 2
            while not self.agent._archive_lock.locked() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(self.agent._archive_lock.locked())
            started = time.monotonic()
            status, body = self.upload(str(uuid.uuid4()), b"PK")
            self.assertEqual(429, status, body)
            self.assertLess(time.monotonic() - started, 1)
            self.assertEqual(200, self.request("GET", "/health")[0])
            connection.send(b"K")
            response = connection.getresponse()
            self.assertEqual(201, response.status, response.read())
        finally:
            connection.close()

    @unittest.skipUnless(os.name == "posix", "Descriptor nofollow protection requires POSIX")
    def test_archive_rejects_symlink_directory_and_target(self):
        outside = self.directory / "outside"
        outside.mkdir()
        uploads = self.agent.runtime / "uploads"
        uploads.symlink_to(outside, target_is_directory=True)
        identifier = str(uuid.uuid4())
        self.assertGreaterEqual(self.upload(identifier, b"PK")[0], 400)
        self.assertEqual([], list(outside.iterdir()))
        uploads.unlink()
        uploads.mkdir()
        target = outside / "target"
        target.write_bytes(b"owner")
        (uploads / (identifier + ".zip")).symlink_to(target)
        self.assertEqual(409, self.request("DELETE", "/archives/" + identifier)[0])
        self.assertEqual(b"owner", target.read_bytes())

    def test_archive_upload_deadline_cleans_private_partial_file(self):
        import io
        identifier = str(uuid.uuid4())
        with mock.patch.object(runtime, "UPLOAD_TTL_SECONDS", 0):
            with self.assertRaises(runtime.ApiError) as raised:
                self.agent.upload_archive(identifier, io.BytesIO(b"PK"), 2)
        self.assertEqual(408, raised.exception.status)
        self.assertEqual([], list((self.agent.runtime / "uploads").iterdir()))

    def test_all_routes_require_authentication_including_shutdown(self):
        for method, path, body in [
            ("GET", "/layout", None), ("GET", "/processes", None),
            ("GET", "/binaries?name=python", None), ("POST", "/processes", {}),
            ("POST", "/shutdown", {}), ("DELETE", "/processes/missing", None),
            ("PUT", "/health", {}), ("GET", "/does-not-exist", None),
        ]:
            self.assertEqual(401, self.request(method, path, body, token=None)[0])
        self.assertEqual(200, self.request("GET", "/health")[0])

    def test_server_binds_only_ipv4_loopback(self):
        self.assertEqual("127.0.0.1", self.server.server_address[0])

    @unittest.skipUnless(os.name == "posix", "Termux enables POSIX address reuse for immediate restarts")
    def test_fixed_loopback_port_can_restart_after_completed_http_request(self):
        self.assertEqual(200, self.request("GET", "/health")[0])
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=2)
        self.server = runtime.LoopbackHTTPServer(self.port, self.agent)
        self.server_thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.server_thread.start()
        self.assertEqual(200, self.request("GET", "/health")[0])

    def test_layout_creates_private_directories_inside_root(self):
        status, layout = self.request("GET", "/layout")
        self.assertEqual(200, status)
        self.assertEqual({"rootPath", "projectsPath", "nginxConfigPath", "nginxHostsPath",
                          "runtimePath", "logsPath"}, set(layout))
        for name, value in layout.items():
            path = Path(value)
            path.relative_to(self.root.resolve())
            if name != "nginxConfigPath":
                self.assertTrue(path.is_dir())
                if os.name == "posix":
                    self.assertEqual(0o700, path.stat().st_mode & 0o777)

    def test_binary_alias_prefers_termux_prefix_before_global_path(self):
        candidate = self.prefix / "bin/nodejs"
        candidate.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        candidate.chmod(0o700)
        status, body = self.request("GET", "/binaries?name=node")
        self.assertEqual(200, status)
        self.assertEqual(str(candidate.resolve()), body["path"])

    def test_invalid_binary_names_and_missing_binary(self):
        for name in ("../python", "/bin/sh", "a%00b", "a%20b", "a%5Cb"):
            self.assertEqual(400, self.request("GET", "/binaries?name=" + name)[0])
        self.assertEqual(400, self.request("GET", "/binaries?name=a&name=b")[0])
        self.assertEqual(404, self.request("GET", "/binaries?name=notinstalledpocketpaas987")[0])

    def test_argv_preserves_arguments_environment_and_working_directory(self):
        program = "import json,os,sys;print(json.dumps([sys.argv[1],os.getenv('PPAAS_TEST'),os.getcwd(),os.getenv('PATH')]))"
        request = {
            "command": {"kind": "argv", "value": [sys.executable, "-c", program, "a; $HOME | b"]},
            "env": {"PPAAS_TEST": "value with spaces", "PATH": "original-path"},
            "cwd": str(self.agent.projects),
        }
        status, started = self.request("POST", "/processes", request)
        self.assertEqual(201, status, started)
        out, err, code = self.collect(started["id"])
        result = json.loads(out)
        self.assertEqual(["a; $HOME | b", "value with spaces", str(self.agent.projects)], result[:3])
        self.assertEqual(str(self.prefix / "bin") + os.pathsep + "original-path", result[3])
        self.assertEqual(b"", err)
        self.assertEqual(0, code)

    @unittest.skipUnless(os.name == "posix", "Termux shell mode needs a POSIX shell")
    def test_shell_uses_sh_with_environment(self):
        status, started = self.request("POST", "/processes", {
            "command": {"kind": "shell", "value": 'printf "%s" "$PPAAS_TEST"; printf err >&2'},
            "env": {"PPAAS_TEST": "value"}, "cwd": str(self.root),
        })
        self.assertEqual(201, status, started)
        self.assertEqual((b"value", b"err", 0), self.collect(started["id"]))

    def test_stdout_stderr_flood_is_lossless_independent_and_memory_bounded(self):
        program = (
            "import os,threading;"
            "out=bytes(range(256))*4096;err=bytes(reversed(range(256)))*4096;"
            "a=threading.Thread(target=lambda:[os.write(1,out[i:i+8192]) for i in range(0,len(out),8192)]);"
            "b=threading.Thread(target=lambda:[os.write(2,err[i:i+8192]) for i in range(0,len(err),8192)]);"
            "a.start();b.start();a.join();b.join()"
        )
        identifier = self.spawn(program)
        record = self.agent.record(identifier)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and (
                record.stdout.buffered_bytes < runtime.STREAM_CAPACITY or
                record.stderr.buffered_bytes < runtime.STREAM_CAPACITY):
            time.sleep(0.01)
        self.assertEqual(runtime.STREAM_CAPACITY, record.stdout.buffered_bytes)
        self.assertEqual(runtime.STREAM_CAPACITY, record.stderr.buffered_bytes)
        self.assertTrue(all(reader.is_alive() for reader in record._readers))
        out, err, code = self.collect(identifier)
        self.assertEqual(bytes(range(256)) * 4096, out)
        self.assertEqual(bytes(reversed(range(256))) * 4096, err)
        self.assertEqual(0, code)
        self.assertLessEqual(record.stdout.high_water_bytes, runtime.STREAM_CAPACITY)
        self.assertLessEqual(record.stderr.high_water_bytes, runtime.STREAM_CAPACITY)

    def test_eof_is_reported_only_after_pending_stream_bytes_are_drained(self):
        identifier = self.spawn("import os;os.write(1,b'x'*60000)")
        record = self.agent.record(identifier)
        deadline = time.monotonic() + 3
        while not record.stdout.reader_closed and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(record.stdout.reader_closed)
        status, first = self.request("GET", "/processes/" + identifier + "/poll")
        self.assertEqual(200, status)
        self.assertEqual(runtime.POLL_CHUNK_BYTES, len(base64.b64decode(first["stdout"])))
        self.assertFalse(first["stdoutClosed"])
        tail, err, code = self.collect(identifier)
        self.assertEqual(b"x" * 60000, base64.b64decode(first["stdout"]) + tail)
        self.assertEqual(b"", err)
        self.assertEqual(0, code)

    def test_nonzero_exit_and_binary_output_are_preserved(self):
        identifier = self.spawn("import os,sys;os.write(1,b'\\x00\\xff');os.write(2,b'\\xfeerror');sys.exit(17)")
        self.assertEqual((b"\x00\xff", b"\xfeerror", 17), self.collect(identifier))

    def test_stop_reaps_backpressured_process_and_keeps_queued_output(self):
        identifier = self.spawn("import os;\nwhile True:\n os.write(1,b'o'*8192);os.write(2,b'e'*8192)")
        record = self.agent.record(identifier)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and (
                record.stdout.buffered_bytes < runtime.STREAM_CAPACITY or
                record.stderr.buffered_bytes < runtime.STREAM_CAPACITY):
            time.sleep(0.01)
        stdout_count = record.stdout.buffered_bytes
        stderr_count = record.stderr.buffered_bytes
        self.assertGreater(stdout_count, 0)
        self.assertGreater(stderr_count, 0)
        status, response = self.request("POST", "/processes/" + identifier + "/stop", {"graceMillis": 100})
        self.assertEqual(200, status, response)
        self.assertIsNotNone(response["exitCode"])
        self.assertIsNotNone(record.process.poll())
        self.assertTrue(all(not reader.is_alive() for reader in record._readers))
        out, err, _ = self.collect(identifier)
        self.assertGreaterEqual(len(out), stdout_count)
        self.assertGreaterEqual(len(err), stderr_count)
        self.assertEqual(b"o" * len(out), out)
        self.assertEqual(b"e" * len(err), err)

    @unittest.skipUnless(os.name == "posix", "Windows verifies the main child; POSIX verifies process groups")
    def test_stop_kills_term_ignoring_descendant_in_owned_process_group(self):
        heartbeat = self.root / "heartbeat"
        child = ("import os,signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                 "print(os.getpid(),flush=True);\nwhile True:\n"
                 " open(" + repr(str(heartbeat)) + ",'a').write('x');time.sleep(0.02)")
        program = "import subprocess,sys,time;subprocess.Popen([sys.executable,'-c'," + repr(child) + "]);time.sleep(60)"
        identifier = self.spawn(program)
        deadline = time.monotonic() + 3
        pid_bytes = bytearray()
        while b"\n" not in pid_bytes and time.monotonic() < deadline:
            status, response = self.request("GET", "/processes/" + identifier + "/poll?waitMs=100")
            self.assertEqual(200, status)
            pid_bytes.extend(base64.b64decode(response["stdout"]))
        child_pid = int(bytes(pid_bytes).strip())
        self.assertTrue(heartbeat.exists())
        self.assertEqual(200, self.request("POST", "/processes/" + identifier + "/stop", {"graceMillis": 150})[0])
        self.collect(identifier)
        size = heartbeat.stat().st_size
        time.sleep(0.1)
        self.assertEqual(size, heartbeat.stat().st_size)
        try:
            os.kill(child_pid, 0)
        except ProcessLookupError:
            return
        proc_stat = Path("/proc") / str(child_pid) / "stat"
        if proc_stat.exists():
            self.assertEqual("Z", proc_stat.read_text().split(") ", 1)[1].split()[0])

    def test_deletion_rejects_live_or_undrained_and_allows_finished(self):
        identifier = self.spawn("import time;print('hello',flush=True);time.sleep(30)")
        path = "/processes/" + identifier
        self.assertEqual(409, self.request("DELETE", path)[0])
        self.assertEqual(200, self.request("POST", path + "/stop", {"graceMillis": 0})[0])
        if self.agent.record(identifier).stdout.buffered_bytes:
            self.assertEqual(409, self.request("DELETE", path)[0])
        self.collect(identifier)
        self.assertEqual((200, {"deleted": True}), self.request("DELETE", path))
        self.assertEqual(404, self.request("GET", path + "/poll")[0])

    def test_working_directory_validation_rejects_relative_outside_missing_and_file(self):
        file_path = self.root / "file"
        file_path.write_text("x", encoding="utf-8")
        for cwd in (".", str(self.directory), str(self.root / "missing"), str(file_path), "a\x00b"):
            status, response = self.request("POST", "/processes", {
                "command": {"kind": "argv", "value": [sys.executable, "-c", "pass"]},
                "env": {}, "cwd": cwd,
            })
            self.assertEqual(400, status, response)
            self.assertEqual("invalid_cwd", response["code"])

    def test_symlink_escape_cannot_be_used_as_working_directory(self):
        link = self.root / "escape"
        try:
            link.symlink_to(self.directory, target_is_directory=True)
        except (NotImplementedError, OSError):
            self.skipTest("This host does not permit symlinks")
        status, response = self.request("POST", "/processes", {
            "command": {"kind": "argv", "value": [sys.executable, "-c", "pass"]},
            "env": {}, "cwd": str(link),
        })
        self.assertEqual(400, status, response)

    def test_environment_validation_rejects_bad_keys_values_and_types(self):
        for environment in ({"BAD=KEY": "x"}, {"x": "a\x00b"}, {"x": 3}, {"0BAD": "x"}, [],
                            {"v" + str(index): "x" for index in range(129)}):
            status, response = self.request("POST", "/processes", {
                "command": {"kind": "argv", "value": [sys.executable, "-c", "pass"]},
                "env": environment, "cwd": str(self.root),
            })
            self.assertEqual(400, status, response)
            self.assertEqual("invalid_env", response["code"])

    def test_invalid_commands_do_not_create_records(self):
        for command in (None, {}, {"kind": "argv", "value": []}, {"kind": "argv", "value": ["", 1]},
                        {"kind": "shell", "value": ""}, {"kind": "argv", "value": [sys.executable, "a\x00b"]},
                        {"kind": "unknown", "value": "x"}):
            status, response = self.request("POST", "/processes", {
                "command": command, "env": {}, "cwd": str(self.root),
            })
            self.assertEqual(400, status, response)
        self.assertEqual({"processes": []}, self.request("GET", "/processes")[1])

    def test_live_process_limit_is_enforced(self):
        identifiers = [self.spawn("import time;time.sleep(30)") for _ in range(8)]
        status, response = self.request("POST", "/processes", {
            "command": {"kind": "argv", "value": [sys.executable, "-c", "pass"]},
            "env": {}, "cwd": str(self.root),
        })
        self.assertEqual(429, status)
        self.assertEqual("process_limit", response["code"])
        self.assertEqual(8, len(self.request("GET", "/processes")[1]["processes"]))
        self.assertEqual(200, self.request("POST", "/processes/" + identifiers[0] + "/stop", {"graceMillis": 0})[0])
        self.collect(identifiers[0])
        self.collect(self.spawn("pass"))

    def test_json_rejects_duplicate_keys_nonobjects_invalid_encoding_and_nan(self):
        for raw in (b'{"a":1,"a":2}', b'[]', b'{', b'\xff', b'{"a":NaN}'):
            status, body = self.request("POST", "/processes", raw=raw,
                                        extra_headers={"Content-Type": "application/json"})
            self.assertEqual(400, status, body)
            self.assertEqual("invalid_json", body["code"])

    def test_body_size_content_type_and_length_are_checked(self):
        self.assertEqual(413, self.request("POST", "/processes", raw=b"{}", extra_headers={
            "Content-Type": "application/json", "Content-Length": str(runtime.MAX_JSON_BYTES + 1)})[0])
        self.assertEqual(415, self.request("POST", "/processes", raw=b"{}", extra_headers={
            "Content-Type": "text/plain"})[0])
        self.assertEqual(400, self.request("POST", "/processes", raw=b"{}", extra_headers={
            "Content-Type": "application/json", "Content-Length": "-1"})[0])
        self.assertEqual(400, self.request("POST", "/processes", raw=b"{}", extra_headers={
            "Content-Type": "application/json", "Content-Length": "9" * 5000})[0])
        self.assertEqual(400, self.request("POST", "/processes", raw=b"{}", extra_headers={
            "Content-Type": "application/json", "Transfer-Encoding": "chunked"})[0])

    def test_bad_routes_ids_methods_and_poll_parameters(self):
        self.assertEqual(404, self.request("GET", "/unknown")[0])
        self.assertEqual(404, self.request("GET", "/processes/not-a-uuid/poll")[0])
        identifier = self.spawn("import time;time.sleep(30)")
        path = "/processes/" + identifier
        self.assertEqual(405, self.request("PUT", path + "/poll", {})[0])
        for query in ("waitMs=-1", "waitMs=1001", "waitMs=true", "waitMs=0&waitMs=1", "unexpected=1"):
            self.assertEqual(400, self.request("GET", path + "/poll?" + query)[0])
        for grace in (-1, 10001, True, "100"):
            self.assertEqual(400, self.request("POST", path + "/stop", {"graceMillis": grace})[0])

    def test_stop_is_idempotent(self):
        identifier = self.spawn("import time;time.sleep(30)")
        path = "/processes/" + identifier + "/stop"
        first = self.request("POST", path, {"graceMillis": 0})
        second = self.request("POST", path, {"graceMillis": 0})
        self.assertEqual(200, first[0])
        self.assertEqual(first, second)
        self.collect(identifier)

    def test_finished_records_have_bounded_ttl(self):
        identifier = self.spawn("pass")
        self.collect(identifier)
        record = self.agent.record(identifier)
        record._completed_at = time.monotonic() - runtime.FINISHED_RECORD_TTL_SECONDS - 1
        self.assertEqual(404, self.request("GET", "/processes/" + identifier + "/poll")[0])

    def test_authenticated_activity_refreshes_idle_clock(self):
        self.agent._last_activity = time.monotonic() - runtime.IDLE_TTL_SECONDS - 1
        self.assertTrue(self.agent.idle_expired)
        self.assertEqual(401, self.request("GET", "/health", token=None)[0])
        self.assertTrue(self.agent.idle_expired)
        self.assertEqual(200, self.request("GET", "/health")[0])
        self.assertFalse(self.agent.idle_expired)

    def test_idle_watchdog_shuts_down_server_and_owned_process(self):
        agent = runtime.Agent(TOKEN, root=self.root / "idle", prefix=self.prefix, idle_ttl=0.05)
        started = agent.start({
            "command": {"kind": "argv", "value": [sys.executable, "-c", "import time;time.sleep(30)"]},
            "env": {}, "cwd": str(agent.root),
        })
        record = agent.record(started["id"])
        errors = []

        def run():
            try:
                runtime.serve(agent, 0)
            except Exception as error:
                errors.append(error)

        server_thread = threading.Thread(target=run, daemon=True)
        server_thread.start()
        try:
            server_thread.join(timeout=4)
            self.assertFalse(server_thread.is_alive())
            self.assertEqual([], errors)
            self.assertIsNotNone(record.process.poll())
            self.assertFalse(record.live)
        finally:
            agent.shutdown()

    def test_process_lease_reaps_orphan_even_while_other_activity_keeps_server_alive(self):
        self.agent.shutdown()
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=2)
        self.agent = runtime.Agent(TOKEN, root=self.root / "lease", prefix=self.prefix,
                                   idle_ttl=0.15, process_lease=0.05)
        self.server = runtime.LoopbackHTTPServer(0, self.agent)
        self.port = self.server.server_address[1]
        errors = []

        def run():
            try:
                runtime.serve(self.agent, 0)
            except Exception as error:
                errors.append(error)

        with mock.patch.object(runtime, "LoopbackHTTPServer", return_value=self.server):
            self.server_thread = threading.Thread(target=run, daemon=True)
            self.server_thread.start()
            identifier = self.spawn("import time;time.sleep(30)", cwd=self.agent.root)
            record = self.agent.record(identifier)
            deadline = time.monotonic() + 3
            while record.process.poll() is None and time.monotonic() < deadline:
                self.assertEqual(200, self.request("GET", "/health")[0])
                time.sleep(0.02)
            self.assertIsNotNone(record.process.poll())
            self.assertFalse(record.live)
            self.assertEqual(200, self.request("GET", "/health")[0])
            self.assertTrue(self.server_thread.is_alive())
            self.assertEqual([], errors)
            self.assertEqual(200, self.request("POST", "/shutdown", {})[0])
            self.server_thread.join(timeout=3)
            self.assertFalse(self.server_thread.is_alive())

    def test_shutdown_stops_owned_child_and_server(self):
        identifier = self.spawn("import time;time.sleep(30)")
        record = self.agent.record(identifier)
        self.assertEqual((200, {"stopped": True}), self.request("POST", "/shutdown", {}))
        self.server_thread.join(timeout=4)
        self.assertFalse(self.server_thread.is_alive())
        self.assertIsNotNone(record.process.poll())
        self.assertFalse(record.live)

    def test_invalid_runtime_token_and_symlink_directory_are_rejected(self):
        for value in ("short", "z" * 64, "" , None):
            with self.assertRaises(ValueError):
                runtime.Agent(value, root=self.root, prefix=self.prefix)
        link = self.directory / "linked-root"
        try:
            link.symlink_to(self.root, target_is_directory=True)
        except (NotImplementedError, OSError):
            return
        with self.assertRaises(ValueError):
            runtime.Agent(TOKEN, root=link, prefix=self.prefix)


class QueueVerificationTest(unittest.TestCase):
    def test_backpressure_blocks_producer_until_drain_without_dropping_bytes(self):
        queue = runtime.BoundedByteQueue(4, lambda: None)
        finished = threading.Event()

        def producer():
            queue.put(b"abcdefgh")
            queue.finish()
            finished.set()

        thread = threading.Thread(target=producer)
        thread.start()
        deadline = time.monotonic() + 2
        while queue.buffered_bytes < 4 and time.monotonic() < deadline:
            time.sleep(0.001)
        self.assertEqual(4, queue.buffered_bytes)
        self.assertFalse(finished.is_set())
        first, closed = queue.drain(4)
        self.assertEqual(b"abcd", first)
        self.assertFalse(closed)
        self.assertTrue(finished.wait(2))
        second, closed = queue.drain(4)
        self.assertEqual(b"efgh", second)
        self.assertTrue(closed)
        thread.join(timeout=2)
        self.assertEqual(4, queue.high_water_bytes)

    def test_shutdown_unblocks_producer_and_preserves_previously_queued_bytes(self):
        queue = runtime.BoundedByteQueue(4, lambda: None)
        finished = threading.Event()

        def producer():
            self.assertFalse(queue.put(b"abcdefgh"))
            finished.set()

        thread = threading.Thread(target=producer)
        thread.start()
        deadline = time.monotonic() + 2
        while queue.buffered_bytes < 4 and time.monotonic() < deadline:
            time.sleep(0.001)
        queue.discard_future_writes()
        self.assertTrue(finished.wait(2))
        self.assertEqual((b"abcd", True), queue.drain(4))
        thread.join(timeout=2)


class ReadFailureVerificationTest(unittest.TestCase):
    def test_unexpected_pipe_read_failure_is_visible_instead_of_successful_exit(self):
        process = subprocess.Popen([sys.executable, "-c", "print('output')"],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=os.name == "posix")
        stdout_descriptor = process.stdout.fileno()
        original_read = os.read

        def fail_stdout_read(descriptor, amount):
            if descriptor == stdout_descriptor:
                raise OSError("Injected pipe read failure")
            return original_read(descriptor, amount)

        record = None
        try:
            with mock.patch.object(runtime.os, "read", side_effect=fail_stdout_read):
                record = runtime.ProcessRecord(process)
                deadline = time.monotonic() + 3
                while not record.stdout.reader_closed and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(record.stdout.reader_closed)
            with self.assertRaises(runtime.ApiError) as caught:
                record.poll(0)
            self.assertEqual(503, caught.exception.status)
            self.assertEqual("stream_failed", caught.exception.code)
        finally:
            if record is not None:
                record.stop(0)
            else:
                process.kill()
                process.wait()
                process.stdout.close()
                process.stderr.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
