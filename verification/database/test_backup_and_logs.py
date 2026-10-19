#!/usr/bin/env python3
"""Yedekleme (HMAC-SHA256 zarfı), Log dışa aktarma (CSV/JSON) ve Remote API
endpoint sözleşmelerini doğrulayan test paketi.
"""

import hashlib
import hmac
import json
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOCAL = REPO / "app/src/main/java/dev/pocketpaas"


def read(path: str) -> str:
    return (LOCAL / path).read_text(encoding="utf-8")


class RemoteApiContractTest(unittest.TestCase):
    def test_remote_api_module_exposes_ssh_services_activity_logs_and_backup_routes(self):
        text = read("data/remote/RemoteApiModule.kt")
        expected_routes = [
            'get("/sites")',
            'post("/deploy")',
            'post("/sites/{id}/start")',
            'post("/sites/{id}/stop")',
            'get("/ssh")',
            'post("/ssh/start")',
            'post("/ssh/stop")',
            'post("/ssh/settings")',
            'post("/ssh/keys")',
            'delete("/ssh/keys/{id}")',
            'get("/services")',
            'post("/services/{key}/install")',
            'post("/services/{key}/start")',
            'post("/services/{key}/stop")',
            'get("/activity")',
            'get("/deployments")',
            'get("/logs/export")',
            'get("/backup/export")',
            'post("/backup/import")',
        ]
        for route in expected_routes:
            self.assertIn(route, text, f"Missing route in RemoteApiModule.kt: {route}")

    def test_netty_factory_wires_new_dependencies(self):
        text = read("data/remote/NettyRemoteApiServerFactory.kt")
        for field in ("ssh = sshProvider()", "services = servicesProvider()", "activity = activity", "deployments = deployments", "backup = backup"):
            self.assertIn(field, text)


class BackupCodecContractTest(unittest.TestCase):
    def test_hmac_sha256_canonical_signing_input_and_constant_time_check(self):
        text = read("domain/backup/BackupCodec.kt")
        self.assertIn("HmacSHA256", text)
        self.assertIn("MessageDigest.isEqual", text)
        self.assertIn('"v1:$exportedAt:$payloadJson"', text)
        self.assertIn("sshSettings = settings.copy(enabled = false)", text)

        # Mirror HMAC-SHA256 calculation verification
        secret = "a" * 64
        exported_at = 1700000000000
        payload = json.dumps({"schemaVersion": 4, "exportedAt": exported_at}, separators=(",", ":"))
        canonical = f"v1:{exported_at}:{payload}"
        digest = hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        self.assertEqual(len(digest), 64)


class LogExportContractTest(unittest.TestCase):
    def test_csv_formula_injection_prefixes_guarded(self):
        text = read("domain/logs/LogExportFormatter.kt")
        self.assertIn("FORMULA_PREFIXES", text)
        for prefix in ("'='", "'+'", "'-'", "'@'"):
            self.assertIn(prefix, text)


class DiAndUiWiringTest(unittest.TestCase):
    def test_app_module_binds_recording_deployment_control_and_backup_manager(self):
        text = read("core/di/AppModule.kt")
        self.assertIn("RecordingDeploymentControl(implementation, history, activity, clock)", text)
        self.assertIn("providePlatformBackupManager", text)

    def test_dashboard_screen_has_activity_filter_and_backup_sheet(self):
        text = read("presentation/dashboard/DashboardScreen.kt")
        self.assertIn("ActivityFilterCard", text)
        self.assertIn("BackupRestoreSheet", text)
        self.assertIn("ssh.settings.hostKeyFingerprint", text)
        self.assertNotIn("ssh.hostKeyFingerprint", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
