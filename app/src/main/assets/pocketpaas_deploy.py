"""ZIP deployments with bounded extraction and journaled, reversible swaps.

Paths are anchored to open no-follow descriptors on POSIX. This protects against
ZIP traversal and descendant symlinks; uploaded dependency scripts still execute
as the trusted owner's Termux UID. Windows support is for host verification only.
"""

import contextlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import uuid
import zipfile


MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_EXTRACTED_BYTES = 512 * 1024 * 1024
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_ENTRIES = 10000
MAX_CENTRAL_BYTES = 4 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MAX_PACKAGE_BYTES = 1024 * 1024
MAX_DEPTH = 64
CHUNK_BYTES = 64 * 1024
DOMAIN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
WINDOWS_DEVICE = re.compile(r"(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?\Z", re.I)


class DeploymentError(Exception):
    pass


def reject_link(attributes):
    if (stat.S_ISLNK(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) &
            getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
        raise DeploymentError("symlink_or_reparse_path")


def require_directory(attributes):
    reject_link(attributes)
    if not stat.S_ISDIR(attributes.st_mode):
        raise DeploymentError("path_is_not_a_directory")


def require_regular(attributes):
    reject_link(attributes)
    if not stat.S_ISREG(attributes.st_mode):
        raise DeploymentError("path_is_not_a_regular_file")


class Tree:
    def __init__(self, root):
        self.root = Path(root)
        if not self.root.is_absolute():
            raise DeploymentError("root_must_be_absolute")
        require_directory(self.root.lstat())
        self.resolved_root = self.root.resolve(strict=True)
        self.root_fd = None
        if os.name == "posix":
            if not hasattr(os, "O_NOFOLLOW"):
                raise DeploymentError("safe_descriptors_unavailable")
            self.root_fd = os.open(str(self.resolved_root), self.directory_flags())

    def __enter__(self):
        return self

    def __exit__(self, *_):
        if self.root_fd is not None:
            os.close(self.root_fd)

    @staticmethod
    def directory_flags():
        return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)

    def parts(self, path):
        if (not isinstance(path, str) or len(path) > 4096 or
                any(ord(c) < 32 or ord(c) == 127 for c in path)):
            raise DeploymentError("invalid_runtime_path")
        if os.name == "posix" and "\\" in path:
            raise DeploymentError("invalid_runtime_path")
        if any(part in (".", "..") for part in path.replace("\\", "/").split("/")):
            raise DeploymentError("invalid_runtime_path")
        candidate = Path(path)
        if not candidate.is_absolute():
            raise DeploymentError("runtime_path_must_be_absolute")
        try:
            parts = candidate.relative_to(self.root).parts
        except ValueError:
            raise DeploymentError("runtime_path_outside_root") from None
        if not parts:
            raise DeploymentError("runtime_subdirectory_required")
        return parts

    @contextlib.contextmanager
    def directory(self, parts, create=False):
        if self.root_fd is not None:
            descriptor = os.dup(self.root_fd)
            try:
                for name in parts:
                    try:
                        child = os.open(name, self.directory_flags(), dir_fd=descriptor)
                    except FileNotFoundError:
                        if not create:
                            raise
                        try:
                            os.mkdir(name, mode=0o700, dir_fd=descriptor)
                            os.fsync(descriptor)
                        except FileExistsError:
                            pass
                        child = os.open(name, self.directory_flags(), dir_fd=descriptor)
                    os.close(descriptor)
                    descriptor = child
                yield descriptor
            finally:
                os.close(descriptor)
        else:
            directory = self.resolved_root
            for name in parts:
                directory = directory / name
                if create:
                    try:
                        directory.mkdir(mode=0o700)
                    except FileExistsError:
                        pass
                require_directory(directory.lstat())
                directory.resolve(strict=True).relative_to(self.resolved_root)
            yield directory

    @staticmethod
    def attributes(directory, name):
        return (os.stat(name, dir_fd=directory, follow_symlinks=False) if isinstance(directory, int)
                else (directory / name).lstat())

    @staticmethod
    def sync(directory):
        if isinstance(directory, int):
            os.fsync(directory)

    def exists_directory(self, parts):
        try:
            with self.directory(parts):
                return True
        except FileNotFoundError:
            return False

    def exists_regular(self, parts):
        try:
            with self.directory(parts[:-1]) as parent:
                require_regular(self.attributes(parent, parts[-1]))
                return True
        except FileNotFoundError:
            return False

    @contextlib.contextmanager
    def open_file(self, parts, write=False, exclusive=False):
        with self.directory(parts[:-1], create=write) as parent:
            flags = (os.O_WRONLY | os.O_CREAT | (os.O_EXCL if exclusive else os.O_TRUNC)
                     if write else os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
            flags |= getattr(os, "O_CLOEXEC", 0)
            if isinstance(parent, int):
                descriptor = os.open(parts[-1], flags | os.O_NOFOLLOW, mode=0o600, dir_fd=parent)
            else:
                if not exclusive:
                    try:
                        require_regular(self.attributes(parent, parts[-1]))
                    except FileNotFoundError:
                        if not write:
                            raise
                descriptor = os.open(str(parent / parts[-1]), flags | os.O_BINARY, mode=0o600)
            try:
                require_regular(os.fstat(descriptor))
                with os.fdopen(descriptor, "wb" if write else "rb", closefd=False) as opened:
                    yield opened
                    if write:
                        opened.flush()
                        os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def read_json(self, parts, limit=MAX_PACKAGE_BYTES):
        with self.open_file(parts) as opened:
            content = opened.read(limit + 1)
        if len(content) > limit:
            raise DeploymentError("json_exceeds_size_limit")
        try:
            value = json.loads(content.decode("utf-8"))
        except (UnicodeError, ValueError):
            raise DeploymentError("invalid_json_file") from None
        if not isinstance(value, dict):
            raise DeploymentError("json_object_required")
        return value

    def atomic_json(self, parts, value):
        temporary = parts[:-1] + (".journal-" + uuid.uuid4().hex,)
        try:
            with self.open_file(temporary, write=True, exclusive=True) as opened:
                opened.write(json.dumps(value, separators=(",", ":")).encode("utf-8"))
            self.rename(temporary, parts, replace=True)
        finally:
            self.unlink(temporary)

    def rename(self, source, destination, replace=False):
        with self.directory(source[:-1]) as src, self.directory(destination[:-1], create=True) as dst:
            if not replace:
                try:
                    self.attributes(dst, destination[-1])
                except FileNotFoundError:
                    pass
                else:
                    raise DeploymentError("rename_destination_exists")
            if isinstance(src, int):
                os.replace(source[-1], destination[-1], src_dir_fd=src, dst_dir_fd=dst)
            else:
                os.replace(src / source[-1], dst / destination[-1])
            self.sync(src)
            self.sync(dst)

    def unlink(self, parts):
        try:
            with self.directory(parts[:-1]) as parent:
                require_regular(self.attributes(parent, parts[-1]))
                if isinstance(parent, int):
                    os.unlink(parts[-1], dir_fd=parent)
                else:
                    (parent / parts[-1]).unlink()
                self.sync(parent)
        except FileNotFoundError:
            pass

    def remove_directory(self, parts):
        # Callers only provide generated, journal-owned staging/backup/active paths.
        try:
            with self.directory(parts[:-1]) as parent:
                try:
                    attributes = self.attributes(parent, parts[-1])
                except FileNotFoundError:
                    return
                require_directory(attributes)
                self._remove_child(parent, parts[-1], 0)
                self.sync(parent)
        except FileNotFoundError:
            # A missing ancestor is harmless; partial descendant cleanup is not.
            if self.exists_directory(parts):
                raise

    def _remove_child(self, parent, name, depth):
        if depth > 128:
            raise DeploymentError("cleanup_depth_exceeded")
        if isinstance(parent, int):
            child = os.open(name, self.directory_flags(), dir_fd=parent)
            try:
                with os.scandir(child) as entries:
                    for entry in entries:
                        attributes = entry.stat(follow_symlinks=False)
                        if stat.S_ISDIR(attributes.st_mode) and not stat.S_ISLNK(attributes.st_mode):
                            self._remove_child(child, entry.name, depth + 1)
                        else:
                            os.unlink(entry.name, dir_fd=child)
            finally:
                os.close(child)
            os.rmdir(name, dir_fd=parent)
        else:
            child = parent / name
            require_directory(child.lstat())
            with os.scandir(child) as entries:
                for entry in entries:
                    attributes = entry.stat(follow_symlinks=False)
                    reparse = getattr(attributes, "st_file_attributes", 0) & 0x400
                    if stat.S_ISDIR(attributes.st_mode) and not reparse:
                        self._remove_child(child, entry.name, depth + 1)
                    elif stat.S_ISDIR(attributes.st_mode) and reparse:
                        os.rmdir(entry.path)
                    else:
                        os.unlink(entry.path)
            child.rmdir()


def safe_name(info):
    if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise DeploymentError("unsupported_zip_compression")
    name = info.orig_filename
    if (not name or len(name) > 4096 or "\\" in name or name.startswith("/") or
            any(ord(c) < 32 or ord(c) == 127 for c in name)):
        raise DeploymentError("unsafe_zip_path")
    is_directory = name.endswith("/")
    parts = tuple(name[:-1].split("/") if is_directory else name.split("/"))
    if len(parts) > MAX_DEPTH or any(
            not part or part in (".", "..") or ":" in part or
            part.endswith((".", " ")) or len(part.encode("utf-8")) > 255 or
            WINDOWS_DEVICE.fullmatch(part) for part in parts):
        raise DeploymentError("unsafe_zip_path")
    mode = info.external_attr >> 16
    kind = stat.S_IFMT(mode)
    if kind not in (0, stat.S_IFDIR if is_directory else stat.S_IFREG):
        raise DeploymentError("zip_special_file_rejected")
    if info.flag_bits & 1:
        raise DeploymentError("encrypted_zip_rejected")
    if info.file_size < 0 or info.compress_size < 0 or info.file_size > MAX_FILE_BYTES:
        raise DeploymentError("zip_file_size_limit")
    if is_directory and info.file_size:
        raise DeploymentError("zip_directory_contains_data")
    if info.file_size > MAX_COMPRESSION_RATIO * max(info.compress_size, 1):
        raise DeploymentError("zip_compression_ratio_limit")
    return parts, is_directory


def preflight_archive(uploaded):
    # ZipFile eagerly allocates one ZipInfo for every central record. Bound both
    # the declared count and the actual central records before constructing it.
    size = os.fstat(uploaded.fileno()).st_size
    if size > MAX_ARCHIVE_BYTES or size < 22:
        raise DeploymentError("zip_archive_size_limit")
    tail_size = min(size, 65535 + 22)
    uploaded.seek(size - tail_size)
    tail = uploaded.read(tail_size)
    index = tail.rfind(b"PK\x05\x06")
    if index < 0 or len(tail) - index < 22:
        raise DeploymentError("zip_eocd_missing")
    signature, disk, central_disk, disk_count, count, central_size, offset, comment_size = struct.unpack(
        "<4s4H2LH", tail[index:index + 22])
    if signature != b"PK\x05\x06" or index + 22 + comment_size != len(tail):
        raise DeploymentError("zip_eocd_invalid")
    position = size - tail_size + index
    if (disk or central_disk or disk_count != count or count == 0 or count > MAX_ENTRIES):
        raise DeploymentError("zip_entry_count_or_multidisk_limit")
    if (count == 0xffff or disk_count == 0xffff or central_size == 0xffffffff or offset == 0xffffffff):
        raise DeploymentError("zip64_not_supported")
    if central_size <= 0 or central_size > MAX_CENTRAL_BYTES or offset + central_size > position:
        raise DeploymentError("zip_central_directory_limit")
    if position >= 20:
        uploaded.seek(position - 20)
        if uploaded.read(4) == b"PK\x06\x07":
            raise DeploymentError("zip64_not_supported")
    # Match ZipFile's concatenated-archive offset calculation exactly. Looking
    # only at the declared offset could validate different records from ZipFile.
    central_start = position - central_size
    uploaded.seek(central_start)
    remaining = central_size
    actual_count = 0
    while remaining:
        if remaining < 46:
            raise DeploymentError("zip_central_directory_invalid")
        header = uploaded.read(46)
        if len(header) != 46 or header[:4] != b"PK\x01\x02":
            raise DeploymentError("zip_central_directory_invalid")
        actual_count += 1
        if actual_count > count or actual_count > MAX_ENTRIES:
            raise DeploymentError("zip_actual_entry_count_limit")
        filename_size, extra_size, entry_comment_size = struct.unpack_from("<3H", header, 28)
        entry_disk = struct.unpack_from("<H", header, 34)[0]
        compressed_size, extracted_size = struct.unpack_from("<2L", header, 20)
        local_offset = struct.unpack_from("<L", header, 42)[0]
        if entry_disk or 0xffffffff in (compressed_size, extracted_size, local_offset):
            raise DeploymentError("zip64_or_multidisk_not_supported")
        variable_size = filename_size + extra_size + entry_comment_size
        if not filename_size or 46 + variable_size > remaining:
            raise DeploymentError("zip_central_directory_invalid")
        uploaded.seek(variable_size, os.SEEK_CUR)
        remaining -= 46 + variable_size
    if actual_count != count:
        raise DeploymentError("zip_actual_entry_count_mismatch")
    uploaded.seek(0)
    return count


def validate_entries(archive, expected_count=None):
    infos = archive.infolist()
    if expected_count is not None and len(infos) != expected_count:
        raise DeploymentError("zip_actual_entry_count_mismatch")
    if not infos or len(infos) > MAX_ENTRIES:
        raise DeploymentError("zip_entry_count_limit")
    entries = []
    explicit = set()
    kinds = {}
    spellings = {}
    expanded = 0
    for info in infos:
        parts, directory = safe_name(info)
        key = tuple(part.casefold() for part in parts)
        if key in explicit:
            raise DeploymentError("duplicate_zip_path")
        explicit.add(key)
        for index in range(1, len(key) + 1):
            prefix = key[:index]
            spelling = parts[:index]
            if prefix in spellings and spellings[prefix] != spelling:
                raise DeploymentError("casefold_zip_path_conflict")
            spellings[prefix] = spelling
        for index in range(1, len(key)):
            parent = key[:index]
            if kinds.get(parent) == "file":
                raise DeploymentError("zip_file_directory_conflict")
            kinds[parent] = "directory"
        expected = "directory" if directory else "file"
        if key in kinds and kinds[key] != expected:
            raise DeploymentError("zip_file_directory_conflict")
        kinds[key] = expected
        expanded += info.file_size
        if expanded > MAX_EXTRACTED_BYTES:
            raise DeploymentError("zip_total_size_limit")
        entries.append((info, parts, directory))
    roots = {parts[0].casefold() for _, parts, _ in entries}
    wrapper = len(roots) == 1 and all(directory or len(parts) > 1 for _, parts, directory in entries)
    stripped = [(info, parts[1:] if wrapper else parts, directory) for info, parts, directory in entries]
    if not any(parts and not directory for _, parts, directory in stripped):
        raise DeploymentError("zip_contains_no_files")
    return stripped


class Workspace:
    def __init__(self, tree, projects, runtime, domain, project_type, deployment_id=None):
        self.tree = tree
        self.projects = tree.parts(projects)
        self.runtime = tree.parts(runtime)
        if (self.projects == self.runtime or self.projects[:len(self.runtime)] == self.runtime or
                self.runtime[:len(self.projects)] == self.projects):
            raise DeploymentError("runtime_layout_overlaps")
        if len(domain) > 253 or not all(DOMAIN.fullmatch(part) for part in domain.split(".")):
            raise DeploymentError("noncanonical_site_domain")
        if project_type not in ("STATIC", "DJANGO", "NODEJS", "REACT"):
            raise DeploymentError("invalid_project_type")
        if deployment_id is not None:
            try:
                canonical = str(uuid.UUID(deployment_id))
            except ValueError:
                raise DeploymentError("invalid_deployment_id") from None
            if canonical != deployment_id:
                raise DeploymentError("invalid_deployment_id")
        self.domain = domain
        self.project_type = project_type
        self.deployment_id = deployment_id
        self.active = self.projects + (domain,)
        if deployment_id is not None:
            self.staging = self.projects + (".deploy-" + deployment_id,)
            self.backup = self.projects + (".backup-" + deployment_id,)
            self.archive = self.runtime + ("uploads", deployment_id + ".zip")
            self.journal = self.runtime + ("deployments", deployment_id + ".json")

    def path(self, parts):
        return self.tree.root.joinpath(*parts).as_posix()

    def metadata(self, source, require_react_output=False):
        requirements = self.tree.exists_regular(source + ("requirements.txt",))
        package = self.tree.exists_regular(source + ("package.json",))
        root = self.active
        if self.project_type == "DJANGO":
            if not requirements:
                raise DeploymentError("python_requirements_missing")
            if self.tree.exists_regular(source + ("manage.py",)):
                entry = "DJANGO"
            elif self.tree.exists_regular(source + ("app.py",)):
                entry = "FLASK"
            else:
                raise DeploymentError("python_entrypoint_missing")
        elif self.project_type in ("NODEJS", "REACT"):
            if not package:
                raise DeploymentError("package_json_missing")
            package_json = self.tree.read_json(source + ("package.json",))
            entry = self.project_type
            if entry == "NODEJS" and not self.tree.exists_regular(source + ("index.js",)):
                raise DeploymentError("node_index_missing")
            if entry == "REACT":
                scripts = package_json.get("scripts")
                build = scripts.get("build") if isinstance(scripts, dict) else None
                if not isinstance(build, str) or not build.strip():
                    raise DeploymentError("react_build_script_missing")
                if require_react_output:
                    candidates = [directory for directory in ("dist", "build")
                                  if self.tree.exists_regular(source + (directory, "index.html"))]
                    if not candidates:
                        raise DeploymentError("react_build_output_missing")
                    root = self.active + (candidates[0],)
        else:
            entry = "STATIC"
        return {"rootDir": self.path(root), "entryPoint": entry,
                "hasRequirements": requirements, "hasPackageJson": package}

    def write_journal(self, record, state):
        record["state"] = state
        self.tree.atomic_json(self.journal, record)

    def read_journal(self):
        record = self.tree.read_json(self.journal, limit=8192)
        if (record.get("deploymentId") != self.deployment_id or record.get("domain") != self.domain or
                record.get("projectType") != self.project_type or
                type(record.get("hadPreviousProject")) is not bool or
                record.get("state") not in ("preparing", "prepared", "activating", "active", "aborting",
                                             "rolled_back", "completing")):
            raise DeploymentError("deployment_journal_mismatch")
        return record

    def prepare(self):
        if self.tree.exists_regular(self.journal) or self.tree.exists_directory(self.staging) or self.tree.exists_directory(self.backup):
            raise DeploymentError("deployment_id_already_used")
        previous = self.tree.exists_directory(self.active)
        record = {"deploymentId": self.deployment_id, "domain": self.domain,
                  "projectType": self.project_type, "hadPreviousProject": previous}
        with self.tree.open_file(self.archive) as uploaded:
            count = preflight_archive(uploaded)
            with zipfile.ZipFile(uploaded) as archive:
                entries = validate_entries(archive, expected_count=count)
                self.write_journal(record, "preparing")
                try:
                    with self.tree.directory(self.staging, create=True):
                        pass
                    total = 0
                    for info, parts, directory in entries:
                        if not parts:
                            continue
                        target = self.staging + parts
                        if directory:
                            with self.tree.directory(target, create=True):
                                pass
                            continue
                        count = 0
                        with archive.open(info) as source, self.tree.open_file(target, write=True, exclusive=True) as output:
                            while True:
                                content = source.read(CHUNK_BYTES)
                                if not content:
                                    break
                                count += len(content)
                                total += len(content)
                                if count > MAX_FILE_BYTES or count > info.file_size or total > MAX_EXTRACTED_BYTES:
                                    raise DeploymentError("zip_actual_size_limit")
                                output.write(content)
                        if count != info.file_size:
                            raise DeploymentError("zip_actual_size_mismatch")
                    metadata = self.metadata(self.staging)
                    self.write_journal(record, "prepared")
                except BaseException:
                    self.tree.remove_directory(self.staging)
                    self.tree.unlink(self.journal)
                    raise
        return {"deploymentId": self.deployment_id, "stagingPath": self.path(self.staging),
                "activePath": self.path(self.active), "backupPath": self.path(self.backup),
                "archivePath": self.path(self.archive), "hadPreviousProject": previous, "metadata": metadata}

    def activate(self):
        record = self.read_journal()
        if record["state"] == "active":
            if not self.tree.exists_directory(self.active) or self.tree.exists_directory(self.staging):
                raise DeploymentError("deployment_active_state_mismatch")
            return {"ok": True}
        if record["state"] != "prepared":
            raise DeploymentError("deployment_is_not_prepared")
        if (self.tree.exists_directory(self.active) != record["hadPreviousProject"] or
                not self.tree.exists_directory(self.staging) or self.tree.exists_directory(self.backup)):
            raise DeploymentError("deployment_directory_state_changed")
        self.write_journal(record, "activating")
        if record["hadPreviousProject"]:
            self.tree.rename(self.active, self.backup)
        self.tree.rename(self.staging, self.active)
        self.write_journal(record, "active")
        return {"ok": True}

    def abort(self):
        try:
            record = self.read_journal()
        except FileNotFoundError:
            # No journal proves no ownership of an active directory.
            return {"ok": True}
        state = record["state"]
        if state == "completing":
            raise DeploymentError("deployment_commit_point_passed")
        if state == "rolled_back":
            self.tree.unlink(self.archive)
            self.tree.unlink(self.journal)
            return {"ok": True}
        if state not in ("preparing", "prepared", "aborting"):
            backup = self.tree.exists_directory(self.backup)
            staging = self.tree.exists_directory(self.staging)
            active = self.tree.exists_directory(self.active)
            if record["hadPreviousProject"]:
                if backup:
                    self.write_journal(record, "aborting")
                    if active:
                        self.tree.remove_directory(self.active)
                    self.tree.rename(self.backup, self.active)
                elif not staging:
                    raise DeploymentError("previous_project_backup_missing")
            elif not staging and active:
                self.write_journal(record, "aborting")
                self.tree.remove_directory(self.active)
        elif state == "aborting":
            if self.tree.exists_directory(self.backup):
                # The prior cleanup may have stopped before the old rename.
                self.tree.remove_directory(self.active)
                self.tree.rename(self.backup, self.active)
            elif not record["hadPreviousProject"]:
                self.tree.remove_directory(self.active)
        self.tree.remove_directory(self.staging)
        self.write_journal(record, "rolled_back")
        self.tree.unlink(self.archive)
        self.tree.unlink(self.journal)
        return {"ok": True}

    def complete(self):
        try:
            record = self.read_journal()
        except FileNotFoundError:
            return {"ok": True}
        if record["state"] not in ("active", "completing") or not self.tree.exists_directory(self.active):
            raise DeploymentError("deployment_is_not_active")
        self.write_journal(record, "completing")
        self.tree.remove_directory(self.backup)
        self.tree.unlink(self.archive)
        self.tree.unlink(self.journal)
        return {"ok": True}

    def inspect(self):
        if not self.tree.exists_directory(self.active):
            raise DeploymentError("project_is_not_deployed")
        return {"metadata": self.metadata(self.active, require_react_output=True)}


def journals(tree, projects, runtime):
    directory = tree.parts(runtime) + ("deployments",)
    try:
        with tree.directory(directory) as parent:
            with os.scandir(parent) as entries:
                names = []
                scanned = 0
                for entry in entries:
                    scanned += 1
                    if scanned > 128:
                        raise DeploymentError("recovery_journal_scan_limit")
                    if not entry.name.endswith(".json"):
                        continue
                    require_regular(entry.stat(follow_symlinks=False))
                    identifier = entry.name[:-5]
                    if str(uuid.UUID(identifier)) != identifier or len(names) >= 64:
                        raise DeploymentError("invalid_recovery_journal")
                    names.append(identifier)
    except FileNotFoundError:
        if tree.exists_directory(directory):
            raise DeploymentError("recovery_journal_directory_changed")
        return {"journals": []}
    records = []
    for identifier in sorted(names):
        record = tree.read_json(directory + (identifier + ".json",), limit=8192)
        if not isinstance(record.get("domain"), str) or not isinstance(record.get("projectType"), str):
            raise DeploymentError("invalid_recovery_journal")
        workspace = Workspace(tree, projects, runtime, record.get("domain"), record.get("projectType"), identifier)
        record = workspace.read_journal()
        records.append({key: record[key] for key in ("deploymentId", "domain", "projectType", "state")})
    return {"journals": records}


def operate(root, projects, runtime, operation, domain, project_type, deployment_id=None):
    if operation not in ("prepare", "activate", "abort", "complete", "inspect", "journals"):
        raise DeploymentError("invalid_deployment_operation")
    if (operation in ("inspect", "journals")) != (deployment_id is None):
        raise DeploymentError("invalid_deployment_arguments")
    with Tree(root) as tree:
        if operation == "journals":
            return journals(tree, projects, runtime)
        workspace = Workspace(tree, projects, runtime, domain, project_type, deployment_id)
        return getattr(workspace, operation)()


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    try:
        if len(arguments) not in (6, 7):
            raise DeploymentError("invalid_deployment_arguments")
        result = operate(*arguments)
        response = json.dumps(result, separators=(",", ":")) + "\n"
        if len(response.encode("utf-8")) > 32 * 1024:
            raise DeploymentError("response_size_limit")
        sys.stdout.write(response)
        return 0
    except DeploymentError as failure:
        sys.stderr.write("Deployment workspace failed: " + str(failure) + "\n")
        return 2
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, NotImplementedError):
        sys.stderr.write("Deployment workspace failed: filesystem_or_archive_rejected\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
