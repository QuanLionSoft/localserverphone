package dev.pocketpaas.data.local

import androidx.room.TypeConverter
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus

class SiteTypeConverters {
    @TypeConverter
    fun projectTypeToString(projectType: ProjectType): String = projectType.name

    @TypeConverter
    fun stringToProjectType(value: String): ProjectType = ProjectType.valueOf(value)

    @TypeConverter
    fun siteStatusToString(status: SiteStatus): String = status.name

    @TypeConverter
    fun stringToSiteStatus(value: String): SiteStatus = SiteStatus.valueOf(value)
}
