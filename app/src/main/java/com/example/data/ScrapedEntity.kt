package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scraped_sessions")
data class ScrapedSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val totalAssets: Int = 0,
    val totalSizeMB: Double = 0.0
)

@Entity(tableName = "scraped_assets")
data class ScrapedAsset(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val url: String,
    val fileName: String,
    val category: String, // IMAGE, AUDIO, VIDEO, SCRIPT, DATA, OTHER
    val mimeType: String,
    val sizeBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)
