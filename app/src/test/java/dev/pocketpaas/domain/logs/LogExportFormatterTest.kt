package dev.pocketpaas.domain.logs

import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.model.ProjectType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExportFormatterTest {

    @Test
    fun `activity filter matches by query level and category`() {
        val sshEvent = ActivityEvent(
            id = 1,
            category = ActivityCategory.SSH,
            level = ActivityLevel.INFO,
            message = "SSH anahtarı eklendi: laptop",
            detail = "SHA256:abc",
            timestamp = 1_000L,
        )
        val dbError = ActivityEvent(
            id = 2,
            category = ActivityCategory.DATABASE,
            level = ActivityLevel.ERROR,
            message = "PostgreSQL başlatılamadı",
            detail = "Port 5432 meşgul",
            timestamp = 2_000L,
        )

        assertTrue(ActivityFilter(query = "laptop").matches(sshEvent))
        assertFalse(ActivityFilter(query = "laptop").matches(dbError))
        assertTrue(ActivityFilter(level = ActivityLevel.ERROR).matches(dbError))
        assertFalse(ActivityFilter(level = ActivityLevel.ERROR).matches(sshEvent))
        assertTrue(ActivityFilter(category = ActivityCategory.SSH).matches(sshEvent))
        assertFalse(ActivityFilter(category = ActivityCategory.SSH).matches(dbError))
    }

    @Test
    fun `csv export escapes quotes commas and neutralizes formula injection`() {
        val events = listOf(
            ActivityEvent(
                id = 10,
                category = ActivityCategory.SECURITY,
                level = ActivityLevel.WARNING,
                message = "=cmd|' /C calc'!A0, \"quoted\"",
                detail = "@SUM(1+1)\nsecond line",
                timestamp = 123_456L,
            ),
        )
        val csv = LogExportFormatter.exportActivity(events, LogExportFormat.CSV)
        assertTrue(csv.startsWith("id,timestamp,category,level,message,detail"))
        assertTrue(csv.contains("\"'=cmd|' /C calc'!A0, \"\"quoted\"\"\""))
        assertTrue(csv.contains("\"'@SUM(1+1)\nsecond line\""))
    }

    @Test
    fun `combined json export contains both activity and deployment counts`() {
        val events = listOf(
            ActivityEvent(1, ActivityCategory.SYSTEM, ActivityLevel.INFO, "Başlatıldı", null, 500L),
        )
        val records = listOf(
            DeploymentRecord(
                id = 3,
                siteId = 1,
                domainName = "demo.example",
                projectType = ProjectType.NODEJS,
                status = DeploymentStatus.SUCCEEDED,
                archiveName = "app.zip",
                archiveSizeBytes = 2048L,
                message = null,
                startedAt = 400L,
                finishedAt = 480L,
            ),
        )
        val json = LogExportFormatter.exportCombined(events, records, LogExportFormat.JSON, exportedAt = 999L)
        assertTrue(json.contains("\"exportedAt\": 999"))
        assertTrue(json.contains("\"activityCount\": 1"))
        assertTrue(json.contains("\"deploymentCount\": 1"))
        assertTrue(json.contains("demo.example"))
    }
}
