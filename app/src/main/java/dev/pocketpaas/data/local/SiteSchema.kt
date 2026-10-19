package dev.pocketpaas.data.local

object SiteSchema {
    val statements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `sites` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `domainName` TEXT NOT NULL,
            `internalPort` INTEGER NOT NULL,
            `projectType` TEXT NOT NULL,
            `processId` INTEGER,
            `publicUrl` TEXT,
            `status` TEXT NOT NULL,
            `logs` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sites_domainName` ON `sites` (`domainName`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sites_internalPort` ON `sites` (`internalPort`)",
    )
}
