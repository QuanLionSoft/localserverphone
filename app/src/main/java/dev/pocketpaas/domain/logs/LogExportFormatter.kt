package dev.pocketpaas.domain.logs

import com.google.gson.GsonBuilder
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.DeploymentRecord

object LogExportFormatter {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()

    data class CombinedLogArchive(
        val schemaVersion: Int = 4,
        val exportedAt: Long,
        val activityCount: Int,
        val deploymentCount: Int,
        val activity: List<ActivityEvent>,
        val deployments: List<DeploymentRecord>,
    )

    fun exportActivity(events: List<ActivityEvent>, format: LogExportFormat): String = when (format) {
        LogExportFormat.JSON -> gson.toJson(events)
        LogExportFormat.CSV -> buildString {
            appendLine("id,timestamp,category,level,message,detail")
            for (event in events) {
                append(event.id)
                append(',')
                append(event.timestamp)
                append(',')
                append(escapeCsvCell(event.category.name))
                append(',')
                append(escapeCsvCell(event.level.name))
                append(',')
                append(escapeCsvCell(event.message))
                append(',')
                appendLine(escapeCsvCell(event.detail.orEmpty()))
            }
        }
    }

    fun exportDeployments(records: List<DeploymentRecord>, format: LogExportFormat): String = when (format) {
        LogExportFormat.JSON -> gson.toJson(records)
        LogExportFormat.CSV -> buildString {
            appendLine("id,siteId,domainName,projectType,status,archiveName,archiveSizeBytes,message,startedAt,finishedAt")
            for (record in records) {
                append(record.id)
                append(',')
                append(record.siteId ?: "")
                append(',')
                append(escapeCsvCell(record.domainName))
                append(',')
                append(escapeCsvCell(record.projectType.name))
                append(',')
                append(escapeCsvCell(record.status.name))
                append(',')
                append(escapeCsvCell(record.archiveName.orEmpty()))
                append(',')
                append(record.archiveSizeBytes ?: "")
                append(',')
                append(escapeCsvCell(record.message.orEmpty()))
                append(',')
                append(record.startedAt)
                append(',')
                appendLine(record.finishedAt ?: "")
            }
        }
    }

    fun exportCombined(
        events: List<ActivityEvent>,
        records: List<DeploymentRecord>,
        format: LogExportFormat,
        exportedAt: Long,
    ): String = when (format) {
        LogExportFormat.JSON -> gson.toJson(
            CombinedLogArchive(
                exportedAt = exportedAt,
                activityCount = events.size,
                deploymentCount = records.size,
                activity = events,
                deployments = records,
            ),
        )
        LogExportFormat.CSV -> buildString {
            appendLine("# ACTIVITY EVENTS")
            append(exportActivity(events, LogExportFormat.CSV))
            appendLine("# DEPLOYMENTS")
            append(exportDeployments(records, LogExportFormat.CSV))
        }
    }

    /**
     * RFC 4180 uyumlu CSV hücre kaçışı + tablo formül enjeksiyonu (`=`, `+`, `-`, `@`, `\t`, `\r`) koruması.
     */
    internal fun escapeCsvCell(raw: String): String {
        val cleaned = raw.replace('\u0000', '\uFFFD')
        val guarded = if (cleaned.isNotEmpty() && cleaned[0] in FORMULA_PREFIXES) "'$cleaned" else cleaned
        val needsQuotes = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) {
            "\"${guarded.replace("\"", "\"\"")}\""
        } else {
            guarded
        }
    }

    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
