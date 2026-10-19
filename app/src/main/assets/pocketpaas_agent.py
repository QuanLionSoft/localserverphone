"""Authenticated loopback process transport for Pocket PaaS in Termux.

This runtime is an owner-trusted process supervisor, not a project sandbox.
Commands execute with the Termux application's UID and permissions. Standard
output and error are transported losslessly during normal operation through
independent bounded queues. Explicit stop preserves queued output but discards
unqueued output to guarantee that backpressure cannot prevent shutdown.
"""

import argparse
import base64
import contextlib
import errno
import hmac
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import uuid


PROTOCOL_VERSION = 1
MAX_JSON_BYTES = 128 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_ARCHIVES = 8
UPLOAD_TTL_SECONDS = 120
STREAM_CAPACITY = 64 * 1024
POLL_CHUNK_BYTES = 16 * 1024
MAX_LIVE_PROCESSES = 8
MAX_PROCESS_RECORDS = 128
FINISHED_RECORD_TTL_SECONDS = 300
IDLE_TTL_SECONDS = 300
PROCESS_LEASE_SECONDS = 300
ENV_KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")
BINARY_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,63}\Z")
TOKEN_FORMAT = re.compile(r"[a-fA-F0-9]{64}\Z")


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class BoundedByteQueue:
    """One stream, one condition, bounded memory, and producer backpressure."""

    def __init__(self, capacity, changed):
        self.capacity = capacity
        self._changed = changed
        self._condition = threading.Condition()
        self._bytes = bytearray()
        self._eof = False
        self._discard_new = False
        self.high_water_bytes = 0

    def put(self, data):
        offset = 0
        while offset < len(data):
            with self._condition:
                while len(self._bytes) >= self.capacity and not self._discard_new:
                    self._condition.wait()
                if self._discard_new:
                    return False
                length = min(self.capacity - len(self._bytes), len(data) - offset)
                self._bytes.extend(data[offset:offset + length])
                offset += length
                self.high_water_bytes = max(self.high_water_bytes, len(self._bytes))
            self._changed()
        return True

    def finish(self):
        with self._condition:
            self._eof = True
            self._condition.notify_all()
        self._changed()

    def discard_future_writes(self):
        with self._condition:
            self._discard_new = True
            self._eof = True
            self._condition.notify_all()
        self._changed()

    def drain(self, limit):
        with self._condition:
            output = bytes(self._bytes[:limit])
            del self._bytes[:len(output)]
            closed = self._eof and not self._bytes
            self._condition.notify_all()
            return output, closed

    @property
    def reader_closed(self):
        with self._condition:
            return self._eof

    @property
    def drained_and_closed(self):
        with self._condition:
            return self._eof and not self._bytes

    @property
    def buffered_bytes(self):
        with self._condition:
            return len(self._bytes)


class ProcessRecord:
    def __init__(self, process):
        self.id = str(uuid.uuid4())
        self.process = process
        self.pid = process.pid
        self._condition = threading.Condition()
        self._stop_lock = threading.RLock()
        self._exit_code = None
        self._completed_at = None
        self._stopped = False
        self._stopping = False
        self._reader_error = None
        self._last_poll = time.monotonic()
        self.stdout = BoundedByteQueue(STREAM_CAPACITY, self._notify)
        self.stderr = BoundedByteQueue(STREAM_CAPACITY, self._notify)
        self._readers = [
            threading.Thread(target=self._read, args=(process.stdout, self.stdout), daemon=True),
            threading.Thread(target=self._read, args=(process.stderr, self.stderr), daemon=True),
        ]
        for reader in self._readers:
            reader.start()
        self._waiter = threading.Thread(target=self._wait, daemon=True)
        self._waiter.start()

    def _notify(self):
        with self._condition:
            self._condition.notify_all()

    def _read(self, stream, output):
        try:
            while True:
                block = os.read(stream.fileno(), 8192)
                if not block or not output.put(block):
                    break
        except (OSError, ValueError):
            self._report_read_error()
        finally:
            try:
                stream.close()
            except (OSError, ValueError):
                self._report_read_error()
            finally:
                output.finish()

    def _report_read_error(self):
        with self._condition:
            if not self._stopping:
                self._reader_error = "An owned process output stream could not be read."
                self._condition.notify_all()

    def _wait(self):
        exit_code = self.process.wait()
        with self._condition:
            self._exit_code = exit_code
            self._condition.notify_all()

    @property
    def live(self):
        return (self.process.poll() is None or
                not self.stdout.reader_closed or not self.stderr.reader_closed)

    @property
    def finished_and_drained(self):
        finished = (self.process.poll() is not None and
                    self.stdout.drained_and_closed and self.stderr.drained_and_closed)
        if finished:
            with self._condition:
                if self._completed_at is None:
                    self._completed_at = time.monotonic()
        return finished

    @property
    def completed_at(self):
        with self._condition:
            return self._completed_at

    def poll(self, wait_ms):
        deadline = time.monotonic() + wait_ms / 1000.0
        with self._condition:
            self._last_poll = time.monotonic()
            while True:
                if self._reader_error is not None:
                    raise ApiError(503, "stream_failed", self._reader_error)
                stdout, stdout_closed = self.stdout.drain(POLL_CHUNK_BYTES)
                stderr, stderr_closed = self.stderr.drain(POLL_CHUNK_BYTES)
                exit_code = self.process.poll()
                if (stdout or stderr or (stdout_closed and stderr_closed and exit_code is not None)
                        or time.monotonic() >= deadline):
                    break
                self._condition.wait(max(0, deadline - time.monotonic()))
        self.finished_and_drained
        return {
            "stdout": base64.b64encode(stdout).decode("ascii"),
            "stderr": base64.b64encode(stderr).decode("ascii"),
            "stdoutClosed": stdout_closed,
            "stderrClosed": stderr_closed,
            "exitCode": exit_code,
        }

    def status(self):
        return {
            "id": self.id,
            "pid": self.pid,
            "exitCode": self.process.poll(),
            "stdoutClosed": self.stdout.drained_and_closed,
            "stderrClosed": self.stderr.drained_and_closed,
        }

    def _signal_group(self, force):
        if os.name == "posix":
            try:
                os.killpg(self.pid, signal.SIGKILL if force else signal.SIGTERM)
            except ProcessLookupError:
                pass
        elif self.process.poll() is None:
            try:
                self.process.kill() if force else self.process.terminate()
            except OSError as error:
                if error.errno != errno.ESRCH and self.process.poll() is None:
                    raise

    def _group_alive(self):
        if os.name != "posix":
            return self.process.poll() is None
        try:
            os.killpg(self.pid, 0)
            return True
        except ProcessLookupError:
            return False

    def stop(self, grace_ms):
        with self._stop_lock:
            if self._stopped:
                return self.process.poll()
            with self._condition:
                self._stopping = True
            if (self.process.poll() is not None and self.stdout.reader_closed and
                    self.stderr.reader_closed):
                self._stopped = True
                return self.process.poll()
            self._signal_group(False)
            deadline = time.monotonic() + grace_ms / 1000.0
            while self._group_alive() and time.monotonic() < deadline:
                remaining = deadline - time.monotonic()
                if self.process.poll() is None:
                    try:
                        self.process.wait(timeout=min(0.05, max(0.001, remaining)))
                    except subprocess.TimeoutExpired:
                        pass
                else:
                    threading.Event().wait(min(0.05, max(0, remaining)))
            self._signal_group(True)
            self.stdout.discard_future_writes()
            self.stderr.discard_future_writes()
            try:
                exit_code = self.process.wait(timeout=3)
            except subprocess.TimeoutExpired as error:
                raise ApiError(503, "stop_timeout", "The owned process could not be reaped.") from error
            with self._condition:
                self._exit_code = exit_code
                self._condition.notify_all()
            for reader in self._readers:
                reader.join(timeout=0.5)
            self._stopped = True
            self.finished_and_drained
            return exit_code

    def stop_if_lease_expired(self, lease_seconds):
        with self._stop_lock:
            with self._condition:
                if self._stopped or not self.live or time.monotonic() - self._last_poll < lease_seconds:
                    return
                self._stopping = True
            self.stop(0)


def _safe_directory(path):
    if path.is_symlink():
        raise ValueError("Runtime directories must not be symbolic links.")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.is_dir() or path.is_symlink():
        raise ValueError("Runtime directory is unavailable.")
    if os.name == "posix":
        path.chmod(0o700)
    return path.resolve()


class Agent:
    def __init__(self, token, root=None, prefix=None, idle_ttl=IDLE_TTL_SECONDS,
                 process_lease=PROCESS_LEASE_SECONDS):
        if not isinstance(token, str) or TOKEN_FORMAT.fullmatch(token) is None:
            raise ValueError("A 256-bit hexadecimal token is required.")
        self._authorization = ("Bearer " + token).encode("ascii")
        home = Path(os.environ.get("HOME") or Path.home()).resolve()
        self.root = _safe_directory(Path(root) if root is not None else home / ".pocket-paas")
        self.projects = _safe_directory(self.root / "htdocs")
        nginx = _safe_directory(self.root / "nginx")
        self.nginx_hosts = _safe_directory(nginx / "conf.d")
        self.runtime = _safe_directory(self.root / "runtime")
        self.logs = _safe_directory(self.root / "logs")
        self.nginx_config = nginx / "nginx.conf"
        self.prefix = Path(prefix or os.environ.get("PREFIX", "/data/data/com.termux/files/usr"))
        if not self.prefix.is_absolute():
            raise ValueError("The runtime prefix must be absolute.")
        self._lock = threading.RLock()
        self._archive_lock = threading.Lock()
        self._archive_owners = {}
        self._records = {}
        self._closing = False
        self._resetting = False
        self._last_activity = time.monotonic()
        self.idle_ttl = idle_ttl
        self.process_lease = process_lease

    def authenticated(self, authorization):
        return hmac.compare_digest((authorization or "").encode("utf-8"), self._authorization)

    def touch(self):
        with self._lock:
            self._last_activity = time.monotonic()

    @property
    def idle_expired(self):
        with self._lock:
            return time.monotonic() - self._last_activity >= self.idle_ttl

    def layout(self):
        return {
            "rootPath": str(self.root),
            "projectsPath": str(self.projects),
            "nginxConfigPath": str(self.nginx_config),
            "nginxHostsPath": str(self.nginx_hosts),
            "runtimePath": str(self.runtime),
            "logsPath": str(self.logs),
        }

    @staticmethod
    def _archive_identifier(identifier):
        try:
            if str(uuid.UUID(identifier)) != identifier:
                raise ValueError()
        except (ValueError, AttributeError, TypeError):
            raise ApiError(400, "invalid_archive_id", "A canonical archive UUID is required.")
        return identifier

    @contextlib.contextmanager
    def _archive_slot(self, deletion=False):
        acquired = (self._archive_lock.acquire(timeout=2) if deletion
                    else self._archive_lock.acquire(blocking=False))
        if not acquired:
            raise ApiError(409 if deletion else 429, "archive_storage_busy",
                           "Archive storage is busy; retry the operation.")
        try:
            yield
        finally:
            self._archive_lock.release()

    @contextlib.contextmanager
    def _archives_directory(self):
        """Anchor POSIX operations to root/runtime/uploads without following links."""
        descriptor = None
        if os.name == "posix":
            flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
            descriptor = os.open(str(self.root), flags)
            try:
                for component in ("runtime", "uploads"):
                    try:
                        child = os.open(component, flags, dir_fd=descriptor)
                    except FileNotFoundError:
                        try:
                            os.mkdir(component, mode=0o700, dir_fd=descriptor)
                            os.fsync(descriptor)
                        except FileExistsError:
                            pass
                        child = os.open(component, flags, dir_fd=descriptor)
                    os.close(descriptor)
                    descriptor = child
                yield descriptor
            finally:
                if descriptor is not None:
                    os.close(descriptor)
        else:
            directory = self.root
            for component in ("runtime", "uploads"):
                directory = directory / component
                try:
                    directory.mkdir(mode=0o700)
                except FileExistsError:
                    pass
                attributes = directory.lstat()
                self._reject_archive_link(attributes)
                if not stat.S_ISDIR(attributes.st_mode):
                    raise ApiError(409, "archive_directory_invalid", "Archive storage is not a safe directory.")
                directory.resolve(strict=True).relative_to(self.root)
            yield directory

    @staticmethod
    def _reject_archive_link(attributes):
        if (stat.S_ISLNK(attributes.st_mode) or
                getattr(attributes, "st_file_attributes", 0) &
                getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
            raise ApiError(409, "archive_link_forbidden", "Archive storage must not contain symbolic links.")

    @staticmethod
    def _archive_attributes(directory, name):
        if isinstance(directory, int):
            return os.stat(name, dir_fd=directory, follow_symlinks=False)
        return (directory / name).lstat()

    @staticmethod
    def _archive_unlink(directory, name):
        if isinstance(directory, int):
            os.unlink(name, dir_fd=directory)
        else:
            (directory / name).unlink()

    def upload_archive(self, identifier, source, length, upload_id=None):
        identifier = self._archive_identifier(identifier)
        upload_id = self._archive_identifier(upload_id or str(uuid.uuid4()))
        _integer(length, 1, MAX_ARCHIVE_BYTES, "Archive length")
        started = time.monotonic()
        # Upload IO has its own lock, allowing process polls and leases to continue.
        with self._archive_slot(), self._archives_directory() as directory:
            with self._lock:
                if self._closing:
                    raise ApiError(503, "shutting_down", "The runtime is shutting down.")
            name = identifier + ".zip"
            try:
                self._archive_attributes(directory, name)
            except FileNotFoundError:
                pass
            else:
                raise ApiError(409, "archive_exists", "The archive identifier already exists.")
            with os.scandir(directory) as scan:
                entries = []
                for entry in scan:
                    entries.append(entry.name)
                    if len(entries) >= MAX_ARCHIVES:
                        break
            present = set(entries)
            for owned in list(self._archive_owners):
                if owned not in present:
                    self._archive_owners.pop(owned, None)
            if len(entries) >= MAX_ARCHIVES:
                raise ApiError(429, "archive_limit", "At most eight runtime archives may be stored.")
            for entry in entries:
                attributes = self._archive_attributes(directory, entry)
                self._reject_archive_link(attributes)
                if not stat.S_ISREG(attributes.st_mode) or attributes.st_size > MAX_ARCHIVE_BYTES:
                    raise ApiError(409, "archive_storage_invalid", "Archive storage contains an invalid file.")
            partial = ".upload-" + uuid.uuid4().hex + ".part"
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
            descriptor = (os.open(partial, flags, 0o600, dir_fd=directory) if isinstance(directory, int)
                          else os.open(str(directory / partial), flags, 0o600))
            committed = False
            try:
                with os.fdopen(descriptor, "wb") as target:
                    remaining = length
                    while remaining:
                        if time.monotonic() - started >= UPLOAD_TTL_SECONDS:
                            raise ApiError(408, "archive_upload_timeout", "The archive upload deadline expired.")
                        with self._lock:
                            if self._closing:
                                raise ApiError(503, "shutting_down", "The runtime is shutting down.")
                        block = source.read1(min(remaining, 64 * 1024)) if hasattr(source, "read1") else source.read(min(remaining, 64 * 1024))
                        if not block:
                            raise ApiError(400, "archive_incomplete", "The archive request body is incomplete.")
                        if time.monotonic() - started >= UPLOAD_TTL_SECONDS:
                            raise ApiError(408, "archive_upload_timeout", "The archive upload deadline expired.")
                        target.write(block)
                        remaining -= len(block)
                        self.touch()
                    target.flush()
                    os.fsync(target.fileno())
                try:
                    self._archive_attributes(directory, name)
                except FileNotFoundError:
                    pass
                else:
                    raise ApiError(409, "archive_exists", "The archive identifier already exists.")
                if isinstance(directory, int):
                    # Termux Python disables link/linkat. Rename stays anchored and atomic.
                    # Authenticated API writers share this exclusive lock; the namespace is owner-trusted.
                    os.rename(partial, name, src_dir_fd=directory, dst_dir_fd=directory)
                else:
                    # Windows rename also refuses an existing destination; this is a host-verification adapter.
                    os.rename(str(directory / partial), str(directory / name))
                committed = True
                self._archive_owners[name] = upload_id
                if isinstance(directory, int):
                    os.fsync(directory)
                return {"path": str(self.runtime / "uploads" / name), "bytes": length, "uploadId": upload_id}
            finally:
                try:
                    self._archive_unlink(directory, partial)
                except FileNotFoundError:
                    pass
                if not committed:
                    self.touch()

    def delete_archive(self, identifier, upload_id=None):
        name = self._archive_identifier(identifier) + ".zip"
        if upload_id is not None:
            self._archive_identifier(upload_id)
        with self._archive_slot(deletion=True), self._archives_directory() as directory:
            try:
                attributes = self._archive_attributes(directory, name)
            except FileNotFoundError:
                self._archive_owners.pop(name, None)
                return {"deleted": True}
            if upload_id is not None and self._archive_owners.get(name) != upload_id:
                raise ApiError(409, "archive_owner_mismatch", "The archive belongs to another upload attempt.")
            self._reject_archive_link(attributes)
            if not stat.S_ISREG(attributes.st_mode):
                raise ApiError(409, "archive_storage_invalid", "The archive is not a regular file.")
            self._archive_unlink(directory, name)
            self._archive_owners.pop(name, None)
            if isinstance(directory, int):
                os.fsync(directory)
            return {"deleted": True}

    def binary(self, name):
        if not isinstance(name, str) or BINARY_NAME.fullmatch(name) is None:
            raise ApiError(400, "invalid_binary", "A simple executable name is required.")
        aliases = {
            "python": ["python", "python3"], "python3": ["python3", "python"],
            "pip": ["pip", "pip3"], "pip3": ["pip3", "pip"],
            "node": ["node", "nodejs"], "nodejs": ["nodejs", "node"],
        }.get(name, [name])
        termux_bin = self.prefix / "bin"
        for alias in aliases:
            candidate = termux_bin / alias
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate.resolve())
        for alias in aliases:
            candidate = shutil.which(alias)
            if candidate is not None:
                return str(Path(candidate).resolve())
        raise ApiError(404, "binary_missing", "The requested executable is not installed.")

    def _validated_cwd(self, value):
        if not isinstance(value, str) or not value or "\x00" in value:
            raise ApiError(400, "invalid_cwd", "An absolute runtime working directory is required.")
        candidate = Path(value)
        if not candidate.is_absolute():
            raise ApiError(400, "invalid_cwd", "An absolute runtime working directory is required.")
        try:
            candidate = candidate.resolve(strict=True)
            candidate.relative_to(self.root)
        except (OSError, ValueError, RuntimeError):
            raise ApiError(400, "invalid_cwd", "The working directory must be inside the runtime root.")
        if not candidate.is_dir():
            raise ApiError(400, "invalid_cwd", "The working directory must be an existing directory.")
        return str(candidate)

    def _validated_environment(self, supplied):
        if not isinstance(supplied, dict) or len(supplied) > 128:
            raise ApiError(400, "invalid_env", "Environment must be an object with at most 128 entries.")
        for key, value in supplied.items():
            if (not isinstance(key, str) or ENV_KEY.fullmatch(key) is None or
                    not isinstance(value, str) or "\x00" in value):
                raise ApiError(400, "invalid_env", "Environment keys or values are invalid.")
        environment = dict(os.environ)
        environment.update(supplied)
        prefix_bin = str(self.prefix / "bin")
        path = environment.get("PATH", os.defpath)
        environment["PATH"] = prefix_bin + os.pathsep + path
        environment["PREFIX"] = str(self.prefix)
        return environment

    def _validated_command(self, command):
        if not isinstance(command, dict) or set(command) != {"kind", "value"}:
            raise ApiError(400, "invalid_command", "Command must contain kind and value.")
        value = command["value"]
        if command["kind"] == "shell":
            if not isinstance(value, str) or not value.strip() or "\x00" in value:
                raise ApiError(400, "invalid_command", "A nonempty shell command is required.")
            return [self.binary("sh"), "-c", value]
        if command["kind"] == "argv":
            if (not isinstance(value, list) or not value or len(value) > 1024 or
                    any(not isinstance(arg, str) or "\x00" in arg for arg in value) or not value[0]):
                raise ApiError(400, "invalid_command", "A nonempty argument vector is required.")
            executable = value[0]
            if not Path(executable).is_absolute():
                executable = self.binary(executable)
            elif not Path(executable).is_file() or not os.access(executable, os.X_OK):
                raise ApiError(404, "binary_missing", "The requested executable is not installed.")
            return [executable] + value[1:]
        raise ApiError(400, "invalid_command", "Command kind must be shell or argv.")

    def _prune_locked(self):
        now = time.monotonic()
        for identifier, record in list(self._records.items()):
            if record.finished_and_drained and now - record.completed_at >= FINISHED_RECORD_TTL_SECONDS:
                del self._records[identifier]
        if len(self._records) >= MAX_PROCESS_RECORDS:
            finished = sorted(
                (record for record in self._records.values() if record.finished_and_drained),
                key=lambda record: record.completed_at,
            )
            for record in finished:
                if len(self._records) < MAX_PROCESS_RECORDS:
                    break
                del self._records[record.id]

    def start(self, body):
        if not isinstance(body, dict) or set(body) != {"command", "env", "cwd"}:
            raise ApiError(400, "invalid_request", "Process requests require command, env, and cwd.")
        command = self._validated_command(body["command"])
        environment = self._validated_environment(body["env"])
        cwd = self._validated_cwd(body["cwd"])
        with self._lock:
            if self._closing or self._resetting:
                raise ApiError(503, "shutting_down", "The runtime is shutting down.")
            self._prune_locked()
            if sum(record.live for record in self._records.values()) >= MAX_LIVE_PROCESSES:
                raise ApiError(429, "process_limit", "The runtime allows at most eight live processes.")
            if len(self._records) >= MAX_PROCESS_RECORDS:
                raise ApiError(429, "record_limit", "Drain or delete completed process records first.")
            try:
                process = subprocess.Popen(
                    command, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    start_new_session=os.name == "posix", close_fds=True,
                )
            except (OSError, ValueError) as error:
                raise ApiError(422, "spawn_failed", "The requested process could not be started.") from error
            try:
                record = ProcessRecord(process)
            except BaseException:
                if os.name == "posix":
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                else:
                    process.kill()
                process.wait()
                process.stdout.close()
                process.stderr.close()
                raise
            self._records[record.id] = record
            return {"id": record.id, "pid": record.pid}

    def record(self, identifier):
        try:
            if str(uuid.UUID(identifier)) != identifier:
                raise ValueError()
        except (ValueError, AttributeError):
            raise ApiError(404, "process_missing", "The requested process record does not exist.")
        with self._lock:
            self._prune_locked()
            record = self._records.get(identifier)
            if record is None:
                raise ApiError(404, "process_missing", "The requested process record does not exist.")
            return record

    def statuses(self):
        with self._lock:
            self._prune_locked()
            return {"processes": [record.status() for record in self._records.values()]}

    def forget(self, identifier):
        with self._lock:
            record = self.record(identifier)
            if not record.finished_and_drained:
                raise ApiError(409, "process_not_drained", "Finish and drain both streams before deletion.")
            del self._records[identifier]

    def shutdown(self):
        with self._lock:
            if self._closing:
                return
            self._closing = True
            records = list(self._records.values())
        for record in records:
            if record.live:
                try:
                    record.stop(0)
                except (OSError, ApiError):
                    # Continue reaping other owned processes if one has failed.
                    pass

    def reset_runtime(self, body):
        if not isinstance(body, dict) or set(body) != {"persistedPids"}:
            raise ApiError(400, "invalid_request", "Runtime reset requires persistedPids.")
        pids = body["persistedPids"]
        if (not isinstance(pids, list) or len(pids) > 64 or
                any(type(pid) is not int or not 1 <= pid <= 2147483647 for pid in pids) or
                len(set(pids)) != len(pids)):
            raise ApiError(400, "invalid_parameter", "At most 64 unique positive PIDs are permitted.")
        if not self._archive_lock.acquire(blocking=False):
            raise ApiError(409, "archive_storage_busy", "Finish the archive operation before reset.")
        entered = False
        try:
            with self._lock:
                if self._closing or self._resetting:
                    raise ApiError(409, "runtime_reset_busy", "The runtime cannot enter reset.")
                self._resetting = True
                entered = True
                records = list(self._records.values())
            failures = []
            for record in records:
                try:
                    record.stop(0)
                except (OSError, ApiError):
                    failures.append(record.id)
            deadline = time.monotonic() + 3
            while any(record._group_alive() for record in records) and time.monotonic() < deadline:
                threading.Event().wait(0.05)
            if failures or any(record._group_alive() for record in records):
                raise ApiError(503, "runtime_reset_unconfirmed", "Owned process groups could not be confirmed stopped.")
            unresolved = []
            for pid in pids:
                if _pid_exists(pid):
                    unresolved.append(pid)
            with self._lock:
                self._records.clear()
            return {"stopped": True, "liveProcesses": 0, "unconfirmedPids": unresolved}
        finally:
            if entered:
                with self._lock:
                    self._resetting = False
            self._archive_lock.release()

    def expire_process_leases(self):
        with self._lock:
            records = list(self._records.values())
        for record in records:
            try:
                record.stop_if_lease_expired(self.process_lease)
            except (OSError, ApiError):
                # A single failed stop must not disable the other process leases.
                pass


def _pid_exists(pid):
    if os.name == "posix":
        try:
            # Probe only: signaling a guessed/reused PID is forbidden.
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except (PermissionError, OSError):
            return True
    # Host verification only. Windows os.kill(pid, 0) terminates a process;
    # use a read-only synchronization handle instead.
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
    handle = kernel.OpenProcess(0x00100000, False, pid)
    if not handle:
        return ctypes.get_last_error() != 87
    try:
        return kernel.WaitForSingleObject(handle, 0) != 0
    finally:
        kernel.CloseHandle(handle)

def _integer(value, minimum, maximum, label):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ApiError(400, "invalid_parameter", label + " is outside the permitted range.")
    return value


def _unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON keys are forbidden.")
        result[key] = value
    return result


class LoopbackHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    allow_reuse_address = os.name == "posix"
    request_queue_size = 16

    def __init__(self, port, agent):
        self.agent = agent
        self._handler_slots = threading.BoundedSemaphore(32)
        super().__init__(("127.0.0.1", port), AgentRequestHandler)

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(10)
        return request, address

    def process_request(self, request, client_address):
        if not self._handler_slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._handler_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._handler_slots.release()

    def handle_error(self, request, client_address):
        # Default traceback logging can expose request data and credentials.
        pass


class AgentRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    server_version = "PocketPaaSRuntime/1"
    sys_version = ""

    def log_message(self, format_string, *arguments):
        pass

    def _send(self, status, body):
        data = json.dumps(body, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def send_error(self, code, message=None, explain=None):
        self._send(code, {"error": "The HTTP request is invalid.", "code": "invalid_http"})

    def _json_body(self):
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") is not None:
            raise ApiError(400, "invalid_body", "Transfer encoding is not supported.")
        if not lengths:
            raise ApiError(411, "length_required", "Content-Length is required.")
        if len(lengths) != 1 or re.fullmatch(r"[0-9]{1,10}", lengths[0]) is None:
            raise ApiError(400, "invalid_body", "Content-Length is invalid.")
        length = int(lengths[0])
        if length > MAX_JSON_BYTES:
            raise ApiError(413, "body_limit", "The JSON request exceeds 128 KiB.")
        if length == 0:
            raise ApiError(400, "invalid_body", "A JSON object is required.")
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            raise ApiError(415, "content_type", "Content-Type must be application/json.")
        data = self.rfile.read(length)
        if len(data) != length:
            raise ApiError(400, "invalid_body", "The JSON request is incomplete.")
        try:
            body = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_json_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError()))
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise ApiError(400, "invalid_json", "A valid JSON object is required.")
        if not isinstance(body, dict):
            raise ApiError(400, "invalid_json", "A JSON object is required.")
        return body

    def _archive_length(self):
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") is not None:
            raise ApiError(400, "invalid_body", "Transfer encoding is not supported.")
        if not lengths:
            raise ApiError(411, "length_required", "Content-Length is required.")
        if len(lengths) != 1 or re.fullmatch(r"[0-9]{1,10}", lengths[0]) is None:
            raise ApiError(400, "invalid_body", "Content-Length is invalid.")
        length = int(lengths[0])
        if length < 1 or length > MAX_ARCHIVE_BYTES:
            raise ApiError(413, "archive_limit", "Archive bodies must contain 1 byte to 64 MiB.")
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/zip":
            raise ApiError(415, "content_type", "Content-Type must be application/zip.")
        return length

    def _archive_upload_id(self):
        values = self.headers.get_all("X-PocketPaaS-Upload-Id", [])
        if not values:
            return None
        if len(values) != 1:
            raise ApiError(400, "invalid_archive_upload_id", "One archive upload identifier is permitted.")
        return self.server.agent._archive_identifier(values[0])

    def _archive_body(self):
        handler = self

        class CountedBody:
            def read1(self, length):
                block = handler.rfile.read1(length)
                handler._archive_bytes_read += len(block)
                return block

        return CountedBody()

    def _drain_rejected_archive_body(self):
        """A tiny bounded drain avoids a TCP reset hiding a small request rejection."""
        if self.command != "PUT" or not self.path.startswith("/archives/"):
            return
        lengths = self.headers.get_all("Content-Length", [])
        if (len(lengths) != 1 or re.fullmatch(r"[0-9]{1,10}", lengths[0]) is None or
                self.headers.get("Transfer-Encoding") is not None):
            return
        length = int(lengths[0])
        if length > 4096:
            return
        remaining = length - self._archive_bytes_read
        if remaining <= 0:
            return
        previous = self.connection.gettimeout()
        deadline = time.monotonic() + 0.05
        try:
            while remaining > 0:
                allowance = deadline - time.monotonic()
                if allowance <= 0:
                    break
                self.connection.settimeout(allowance)
                block = self.rfile.read1(remaining)
                if not block:
                    break
                remaining -= len(block)
        except (OSError, ValueError):
            pass
        finally:
            self.connection.settimeout(previous)

    def _dispatch(self):
        agent = self.server.agent
        authorization = self.headers.get_all("Authorization", [])
        if len(authorization) != 1 or not agent.authenticated(authorization[0]):
            raise ApiError(401, "unauthorized", "A valid Bearer token is required.")
        agent.touch()
        parsed = urlsplit(self.path)
        if parsed.scheme or parsed.netloc or parsed.fragment:
            raise ApiError(400, "invalid_target", "A relative request target is required.")
        try:
            query = parse_qs(parsed.query, strict_parsing=True, max_num_fields=8)
        except ValueError:
            raise ApiError(400, "invalid_parameter", "Query parameters are invalid.")
        path = parsed.path
        method = self.command
        if method == "GET" and path == "/health" and not query:
            self._send(200, {"protocolVersion": PROTOCOL_VERSION})
        elif method == "GET" and path == "/capabilities" and not query:
            self._send(200, {"archiveTransferVersion": 1, "cleanupOwnershipVersion": 1,
                             "runtimeResetVersion": 1,
                             "maximumArchiveBytes": MAX_ARCHIVE_BYTES})
        elif method == "POST" and path == "/runtime/reset" and not query:
            self._send(200, agent.reset_runtime(self._json_body()))
        elif re.fullmatch(r"/archives/[a-f0-9\-]+", path) and not query:
            identifier = path.rsplit("/", 1)[1]
            if method == "PUT":
                self._send(201, agent.upload_archive(identifier, self._archive_body(), self._archive_length(), self._archive_upload_id()))
            elif method == "DELETE":
                self._send(200, agent.delete_archive(identifier, self._archive_upload_id()))
            else:
                raise ApiError(405, "method_not_allowed", "The requested archive method is not supported.")
        elif method == "GET" and path == "/layout" and not query:
            self._send(200, agent.layout())
        elif method == "GET" and path == "/binaries":
            if set(query) != {"name"} or len(query["name"]) != 1:
                raise ApiError(400, "invalid_parameter", "One binary name is required.")
            self._send(200, {"path": agent.binary(query["name"][0])})
        elif method == "GET" and path == "/processes" and not query:
            self._send(200, agent.statuses())
        elif method == "POST" and path == "/processes" and not query:
            self._send(201, agent.start(self._json_body()))
        elif method == "POST" and path == "/shutdown" and not query:
            body = self._json_body()
            if body:
                raise ApiError(400, "invalid_request", "Shutdown requires an empty JSON object.")
            self._send(200, {"stopped": True})
            threading.Thread(target=self._shutdown_server, daemon=True).start()
        else:
            match = re.fullmatch(r"/processes/([a-f0-9\-]+)(/poll|/stop)?", path)
            if match is None:
                raise ApiError(404, "route_missing", "The requested route does not exist.")
            record = agent.record(match.group(1))
            action = match.group(2)
            if method == "GET" and action == "/poll":
                if any(key != "waitMs" for key in query) or len(query.get("waitMs", ["0"])) != 1:
                    raise ApiError(400, "invalid_parameter", "One waitMs value is permitted.")
                raw_wait = query.get("waitMs", ["0"])[0]
                if not re.fullmatch(r"[0-9]{1,4}", raw_wait):
                    raise ApiError(400, "invalid_parameter", "waitMs must be between 0 and 1000.")
                wait_ms = _integer(int(raw_wait), 0, 1000, "waitMs")
                self._send(200, record.poll(wait_ms))
            elif method == "POST" and action == "/stop" and not query:
                body = self._json_body()
                if set(body) != {"graceMillis"}:
                    raise ApiError(400, "invalid_request", "Stop requires graceMillis.")
                grace_ms = _integer(body["graceMillis"], 0, 10000, "graceMillis")
                self._send(200, {"exitCode": record.stop(grace_ms)})
            elif method == "DELETE" and action is None and not query:
                agent.forget(record.id)
                self._send(200, {"deleted": True})
            else:
                raise ApiError(405, "method_not_allowed", "The requested method is not supported.")

    def _shutdown_server(self):
        self.server.agent.shutdown()
        self.server.shutdown()

    def _handle(self):
        self._archive_bytes_read = 0
        try:
            self._dispatch()
        except ApiError as error:
            self._drain_rejected_archive_body()
            self._send(error.status, {"error": error.message, "code": error.code})
        except (ConnectionError, socket.timeout, BrokenPipeError):
            self.close_connection = True
        except Exception:
            self._send(500, {"error": "The runtime request failed.", "code": "runtime_error"})

    do_GET = _handle
    do_POST = _handle
    do_DELETE = _handle
    do_PUT = _handle
    do_PATCH = _handle
    do_OPTIONS = _handle
    do_HEAD = _handle


def serve(agent, port):
    server = LoopbackHTTPServer(port, agent)
    shutdown_event = threading.Event()

    def idle_watchdog():
        while not shutdown_event.wait(1):
            agent.expire_process_leases()
            if agent.idle_expired:
                agent.shutdown()
                server.shutdown()
                break

    watchdog = threading.Thread(target=idle_watchdog, daemon=True)
    watchdog.start()
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        shutdown_event.set()
        agent.shutdown()
        server.server_close()
        watchdog.join(timeout=1.5)


def main():
    parser = argparse.ArgumentParser(description="Pocket PaaS Termux runtime")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--token", required=True)
    arguments = parser.parse_args()
    if not 1024 <= arguments.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    if TOKEN_FORMAT.fullmatch(arguments.token) is None:
        parser.error("token must contain exactly 64 hexadecimal characters")
    agent = Agent(arguments.token)

    def terminate(signum, frame):
        raise KeyboardInterrupt()

    if os.name == "posix":
        signal.signal(signal.SIGTERM, terminate)
    try:
        serve(agent, arguments.port)
    except KeyboardInterrupt:
        agent.shutdown()


if __name__ == "__main__":
    main()
