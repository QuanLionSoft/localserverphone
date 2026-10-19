#!/usr/bin/env python3
"""Saf domain politikalarını Python'da ayna testlerle doğrular.

Kotlin dosyalarındaki sabitleri çıkarır ve kuralları gerçek SQLite yerine
saf mantık üzerinden test eder. Ağ/Termux olmadan çalışır.
"""

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOCAL = REPO / "app/src/main/java/dev/pocketpaas"


def read(path: str) -> str:
    return (LOCAL / path).read_text(encoding="utf-8")


class SshPolicyTest(unittest.TestCase):
    def _kotlin_int(self, raw: str) -> int:
        return int(raw.replace("_", ""))

    def policy(self):
        text = read("core/config/SshPolicy.kt")
        default = self._kotlin_int(re.search(r"defaultPort.*=\s*([\d_]+)", text).group(1))
        minimum = self._kotlin_int(re.search(r"minimumPort.*=\s*([\d_]+)", text).group(1))
        maximum = self._kotlin_int(re.search(r"maximumPort.*=\s*([\d_]+)", text).group(1))
        reserved = [self._kotlin_int(x.strip()) for x in re.findall(r"reservedPorts.*setOf\(([^)]+)\)", text)[0].split(",")]
        return default, minimum, maximum, reserved

    def test_default_port_is_unprivileged_and_not_reserved(self):
        default, minimum, maximum, reserved = self.policy()
        self.assertGreaterEqual(default, minimum)
        self.assertLessEqual(default, maximum)
        self.assertNotIn(default, reserved)

    def test_api_and_nginx_ports_are_reserved(self):
        _, _, _, reserved = self.policy()
        self.assertIn(8080, reserved)
        self.assertIn(8081, reserved)

    def test_is_allowed_port_logic(self):
        default, minimum, maximum, reserved = self.policy()

        def is_allowed(port: int) -> bool:
            return minimum <= port <= maximum and port not in reserved

        self.assertTrue(is_allowed(8022))
        self.assertFalse(is_allowed(8080))
        self.assertFalse(is_allowed(22))
        self.assertFalse(is_allowed(99999))


class ServicePolicyTest(unittest.TestCase):
    def test_maximum_running_database_services_fits_process_budget(self):
        text = read("core/config/ServicePolicy.kt")
        max_db = int(re.search(r"maximumRunningDatabaseServices.*=\s*([\d_]+)", text).group(1).replace("_", ""))
        self.assertGreaterEqual(max_db, 1)
        self.assertLessEqual(max_db, 3)

        # Toplam 8 süreç, 1 komut slotu, 2 daemon ayrılmış -> en fazla 5 backend
        # 2 veritabanı + 5 backend + 2 daemon + 1 komut = 10 > 8 olmamalı;
        # bu politika yalnız veritabanı slotunu sınırlar, backend sınırı DeploymentPolicy'dedir.
        self.assertEqual(max_db, 2)

    def test_service_catalog_database_ports_are_unprivileged(self):
        text = read("domain/services/ServiceCatalog.kt")
        ports = [int(x) for x in re.findall(r"port\s*=\s*(\d+)", text)]
        for port in ports:
            self.assertGreaterEqual(port, 1024)
            self.assertLessEqual(port, 65535)


class SshKeyFormatTest(unittest.TestCase):
    def test_supported_types_are_known(self):
        text = read("domain/ssh/SshKeyFormat.kt")
        self.assertIn("ssh-ed25519", text)
        self.assertIn("ssh-rsa", text)
        self.assertIn("ecdsa-sha2-nistp256", text)

    def test_maximum_line_characters_is_reasonable(self):
        text = read("domain/ssh/SshKeyFormat.kt")
        maximum = int(re.search(r"MAXIMUM_LINE_CHARACTERS\s*=\s*([\d_]+)", text).group(1).replace("_", ""))
        self.assertGreaterEqual(maximum, 4096)
        self.assertLessEqual(maximum, 16384)

    def test_fingerprint_prefix(self):
        text = read("domain/ssh/SshKeyFormat.kt")
        self.assertIn("SHA256:", text)


class SshdArgumentsTest(unittest.TestCase):
    def test_build_contains_expected_flags(self):
        text = read("domain/ssh/SshdArguments.kt")
        self.assertIn("-D", text)
        self.assertIn("PasswordAuthentication", text)
        self.assertIn("ListenAddress", text)
        self.assertIn("127.0.0.1", text)
        self.assertIn("0.0.0.0", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
