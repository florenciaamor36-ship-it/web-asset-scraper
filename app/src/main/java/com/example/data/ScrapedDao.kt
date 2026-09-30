package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScrapedDao {
    @Query("SELECT * FROM scraped_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ScrapedSession>>

    @Query("SELECT * FROM scraped_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ScrapedSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ScrapedSession)

    @Delete
    suspend fun deleteSession(session: ScrapedSession)

    @Query("SELECT * FROM scraped_assets WHERE sessionId = :sessionId")
    fun getAssetsForSession(sessionId: String): Flow<List<ScrapedAsset>>

    @Query("SELECT * FROM scraped_assets WHERE sessionId = :sessionId")
    suspend fun getAssetsForSessionSync(sessionId: String): List<ScrapedAsset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<ScrapedAsset>)

    @Query("DELETE FROM scraped_assets WHERE sessionId = :sessionId")
    suspend fun deleteAssetsForSession(sessionId: String)
}
