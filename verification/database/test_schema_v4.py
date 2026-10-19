#!/usr/bin/env python3
"""Room 4 şemasını gerçek SQLite üzerinde doğrular.

Kotlin şema/migration kaynaklarındaki SQL'i çıkarır, 1→2→3→4 migration zincirini
çalıştırır ve üretilen şemayı dışa aktarılan 4.json ile karşılaştırır.
"""

import json
import re
import sqlite3
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LOCAL_SOURCES = REPO_ROOT / "app/src/main/java/dev/pocketpaas/data/local"
SCHEMA_EXPORT = (
    REPO_ROOT / "app/schemas/dev.pocketpaas.data.local.PocketPaaSDatabase/4.json"
)

TRIPLE_QUOTED = re.compile(r'"""(.*?)"""', re.DOTALL)
SINGLE_QUOTED = re.compile(r'"((?:CREATE|ALTER)[^"]*)"')


def extract_sql(path: Path):
    text = path.read_text(encoding="utf-8")
    statements = []
    for match in TRIPLE_QUOTED.finditer(text):
        statements.append(match.group(1).strip())
    for match in SINGLE_QUOTED.finditer(text):
        statements.append(match.group(1).strip())
    return statements


def feature_statements():
    return extract_sql(LOCAL_SOURCES / "PlatformFeatureSchemas.kt")


def site_statements():
    return extract_sql(LOCAL_SOURCES / "SiteSchema.kt")


def server_state_statements():
    return extract_sql(LOCAL_SOURCES / "ServerStateSchema.kt")


def table_info(connection, table):
    return {
        row[1]: row for row in connection.execute(f"PRAGMA table_info(`{table}`)")
    }


def table_names(connection):
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' "
        "AND name != 'room_master_table'"
    )
    return {row[0] for row in rows}


def index_names(connection):
    rows = connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'"
    )
    return {row[0] for row in rows}


def create_version_one(connection):
    """Faz 1 şeması: yalnız platform_settings, publicUrl yok."""
    connection.execute(
        "CREATE TABLE IF NOT EXISTS `platform_settings` ("
        "`id` INTEGER NOT NULL, `installationId` TEXT NOT NULL, "
        "`createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )


class SchemaV4Test(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.addCleanup(self.connection.close)

    def migrate_to_v4(self):
        create_version_one(self.connection)
        for statement in site_statements():
            self.connection.execute(statement)
        for statement in server_state_statements():
            self.connection.execute(statement)
        for statement in feature_statements():
            self.connection.execute(statement)
        self.connection.commit()

    def test_feature_schema_contains_five_tables_and_indices(self):
        statements = feature_statements()
        self.assertGreaterEqual(len(statements), 7)
        for statement in statements:
            self.connection.execute(statement)
        self.connection.commit()
        expected_tables = {
            "activity_events",
            "deployments",
            "services",
            "ssh_settings",
            "ssh_authorized_keys",
        }
        self.assertTrue(expected_tables.issubset(table_names(self.connection)))
        self.assertTrue(
            {
                "index_activity_events_timestamp",
                "index_deployments_siteId",
                "index_deployments_startedAt",
                "index_ssh_authorized_keys_fingerprint",
            }.issubset(index_names(self.connection))
        )

    def test_full_migration_chain_matches_exported_schema(self):
        self.migrate_to_v4()
        exported = json.loads(SCHEMA_EXPORT.read_text(encoding="utf-8"))
        self.assertEqual(exported["database"]["version"], 4)

        exported_tables = {
            entity["tableName"]: entity for entity in exported["database"]["entities"]
        }
        self.assertEqual(exported_tables.keys(), table_names(self.connection))

        for table, entity in exported_tables.items():
            actual = table_info(self.connection, table)
            for field in entity["fields"]:
                column = field["columnName"]
                self.assertIn(column, actual, f"{table}.{column} yok")
                row = actual[column]
                self.assertEqual(
                    row[2].upper(),
                    field["affinity"],
                    f"{table}.{column} tip uyumsuz",
                )
                self.assertEqual(
                    bool(row[3]),
                    field.get("notNull", False),
                    f"{table}.{column} NOT NULL uyumsuz",
                )

    def test_site_schema_columns_survive_feature_migration(self):
        self.migrate_to_v4()
        columns = table_info(self.connection, "sites")
        self.assertIn("publicUrl", columns)
        platform = table_info(self.connection, "platform_settings")
        self.assertIn("publicUrl", platform)

    def test_unique_fingerprint_rejects_duplicates(self):
        self.migrate_to_v4()
        insert = (
            "INSERT INTO ssh_authorized_keys (name, publicKey, fingerprint, enabled, createdAt) "
            "VALUES (?, ?, ?, 1, ?)"
        )
        self.connection.execute(insert, ("laptop", "ssh-ed25519 AAAA key", "SHA256:abc", 1))
        with self.assertRaises(sqlite3.IntegrityError):
            self.connection.execute(insert, ("phone", "ssh-ed25519 AAAA other", "SHA256:abc", 2))

    def test_activity_retention_prune_keeps_newest(self):
        self.migrate_to_v4()
        for index in range(10):
            self.connection.execute(
                "INSERT INTO activity_events (category, level, message, detail, timestamp) "
                "VALUES ('SYSTEM', 'INFO', ?, NULL, ?)",
                (f"event-{index}", index),
            )
        self.connection.execute(
            "DELETE FROM activity_events WHERE id NOT IN "
            "(SELECT id FROM activity_events ORDER BY id DESC LIMIT 3)"
        )
        self.connection.commit()
        rows = self.connection.execute(
            "SELECT message FROM activity_events ORDER BY id DESC"
        ).fetchall()
        self.assertEqual([row[0] for row in rows], ["event-9", "event-8", "event-7"])

    def test_deployment_prune_and_nullable_columns(self):
        self.migrate_to_v4()
        self.connection.execute(
            "INSERT INTO deployments (siteId, domainName, projectType, status, archiveName, "
            "archiveSizeBytes, message, startedAt, finishedAt) "
            "VALUES (NULL, 'demo.example', 'NODEJS', 'FAILED', NULL, NULL, NULL, 5, NULL)"
        )
        self.connection.execute(
            "INSERT INTO deployments (siteId, domainName, projectType, status, archiveName, "
            "archiveSizeBytes, message, startedAt, finishedAt) "
            "VALUES (1, 'demo.example', 'NODEJS', 'SUCCEEDED', 'demo.zip', 1234, NULL, 7, 9)"
        )
        self.connection.commit()
        row = self.connection.execute(
            "SELECT siteId, archiveName, finishedAt FROM deployments ORDER BY id DESC LIMIT 1"
        ).fetchone()
        self.assertEqual(row, (1, "demo.zip", 9))

    def test_services_primary_key_and_boolean_storage(self):
        self.migrate_to_v4()
        self.connection.execute(
            "INSERT INTO services (serviceKey, displayName, category, installed, status, "
            "processId, port, lastError, updatedAt) "
            "VALUES ('redis', 'Redis', 'DATABASE', 1, 'RUNNING', 42, 6379, NULL, 10)"
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.connection.execute(
                "INSERT INTO services (serviceKey, displayName, category, installed, status, "
                "processId, port, lastError, updatedAt) "
                "VALUES ('redis', 'Redis', 'DATABASE', 0, 'STOPPED', NULL, 6379, NULL, 11)"
            )
        row = self.connection.execute(
            "SELECT installed, status, port FROM services WHERE serviceKey = 'redis'"
        ).fetchone()
        self.assertEqual(row, (1, "RUNNING", 6379))


if __name__ == "__main__":
    unittest.main(verbosity=2)
