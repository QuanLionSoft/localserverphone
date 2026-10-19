package dev.pocketpaas.data.local

/** Room 4 şeması için elle yazılmış migration SQL'i; üretim KSP çıktısıyla aynı biçimi kullanır. */
object PlatformFeatureSchemas {
    object ActivityEvents {
        val statements: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `activity_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `category` TEXT NOT NULL,
                `level` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `detail` TEXT,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_activity_events_timestamp` ON `activity_events` (`timestamp`)",
        )
    }

    object Deployments {
        val statements: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `deployments` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `siteId` INTEGER,
                `domainName` TEXT NOT NULL,
                `projectType` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `archiveName` TEXT,
                `archiveSizeBytes` INTEGER,
                `message` TEXT,
                `startedAt` INTEGER NOT NULL,
                `finishedAt` INTEGER
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_deployments_siteId` ON `deployments` (`siteId`)",
            "CREATE INDEX IF NOT EXISTS `index_deployments_startedAt` ON `deployments` (`startedAt`)",
        )
    }

    object Services {
        val statements: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `services` (
                `serviceKey` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `installed` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `processId` INTEGER,
                `port` INTEGER,
                `lastError` TEXT,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`serviceKey`)
            )
            """.trimIndent(),
        )
    }

    object Ssh {
        val statements: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS `ssh_settings` (
                `id` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `port` INTEGER NOT NULL,
                `passwordAuthEnabled` INTEGER NOT NULL,
                `listenLan` INTEGER NOT NULL,
                `hostKeyFingerprint` TEXT,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `ssh_authorized_keys` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `publicKey` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ssh_authorized_keys_fingerprint` ON `ssh_authorized_keys` (`fingerprint`)",
        )
    }

    val statements: List<String> = ActivityEvents.statements + Deployments.statements + Services.statements + Ssh.statements
}
