package dev.pocketpaas.data.local

object ServerStateSchema {
    const val addPublicUrl = "ALTER TABLE platform_settings ADD COLUMN publicUrl TEXT"
}
