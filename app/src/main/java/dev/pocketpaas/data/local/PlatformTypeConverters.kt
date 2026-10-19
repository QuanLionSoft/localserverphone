package dev.pocketpaas.data.local

import androidx.room.TypeConverter
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentStatus
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ServiceCategory

class PlatformTypeConverters {
    @TypeConverter
    fun deploymentStatusToString(value: DeploymentStatus): String = value.name

    @TypeConverter
    fun stringToDeploymentStatus(value: String): DeploymentStatus = DeploymentStatus.valueOf(value)

    @TypeConverter
    fun activityCategoryToString(value: ActivityCategory): String = value.name

    @TypeConverter
    fun stringToActivityCategory(value: String): ActivityCategory = ActivityCategory.valueOf(value)

    @TypeConverter
    fun activityLevelToString(value: ActivityLevel): String = value.name

    @TypeConverter
    fun stringToActivityLevel(value: String): ActivityLevel = ActivityLevel.valueOf(value)

    @TypeConverter
    fun serviceCategoryToString(value: ServiceCategory): String = value.name

    @TypeConverter
    fun stringToServiceCategory(value: String): ServiceCategory = ServiceCategory.valueOf(value)

    @TypeConverter
    fun managedServiceStatusToString(value: ManagedServiceStatus): String = value.name

    @TypeConverter
    fun stringToManagedServiceStatus(value: String): ManagedServiceStatus = ManagedServiceStatus.valueOf(value)
}
