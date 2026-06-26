package com.viel.oto.application.usecase

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupManifest(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val libraryRoots: List<String>,
    val exportedAt: String,
    val databaseVersion: Int = 0
)
