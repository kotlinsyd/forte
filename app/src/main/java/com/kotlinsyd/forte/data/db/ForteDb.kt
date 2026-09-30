package com.kotlinsyd.forte.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.kotlinsyd.forte.data.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationSec: Long,
    val thumbUrl: String?,
    val addedAt: Long,
) {
    fun toTrack() = Track(videoId, title, artist, artistUrl, durationSec, thumbUrl)
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_tracks")
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationSec: Long,
    val thumbUrl: String?,
    val position: Int,
) {
    fun toTrack() = Track(videoId, title, artist, artistUrl, durationSec, thumbUrl)
}

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val videoId: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationSec: Long,
    val thumbUrl: String?,
    val playedAt: Long,
) {
    fun toTrack() = Track(videoId, title, artist, artistUrl, durationSec, thumbUrl)
}

@Entity(tableName = "follows")
data class FollowEntity(
    @PrimaryKey val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val followedAt: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationSec: Long,
    val thumbUrl: String?,
    val filePath: String,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val downloadedAt: Long,
) {
    fun toTrack() = Track(videoId, title, artist, artistUrl, durationSec, thumbUrl)
}

fun Track.toFavorite(now: Long) =
    FavoriteEntity(id, title, artist, artistUrl, durationSec, thumbUrl, now)

fun Track.toHistory(now: Long) =
    HistoryEntity(videoId = id, title = title, artist = artist, artistUrl = artistUrl,
        durationSec = durationSec, thumbUrl = thumbUrl, playedAt = now)

fun Track.toPlaylistTrack(playlistId: Long, position: Int) =
    PlaylistTrackEntity(playlistId = playlistId, videoId = id, title = title, artist = artist,
        artistUrl = artistUrl, durationSec = durationSec, thumbUrl = thumbUrl, position = position)

@Dao
interface ForteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT videoId FROM favorites")
    fun favoriteIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(fav: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE videoId = :id")
    suspend fun removeFavorite(id: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt ASC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun createPlaylist(p: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(p: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(p: PlaylistEntity)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :pid ORDER BY position ASC")
    fun playlistTracks(pid: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :pid")
    suspend fun playlistTrackCount(pid: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :pid")
    fun playlistTrackCountFlow(pid: Long): Flow<Int>

    @Insert
    suspend fun addPlaylistTrack(t: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE rowId = :rowId")
    suspend fun removePlaylistTrack(rowId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :pid")
    suspend fun clearPlaylistTracks(pid: Long)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 200")
    fun history(): Flow<List<HistoryEntity>>

    /** Raw play rows (with repeats) for the listening-stats page. */
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 200")
    fun historyRows(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun addHistory(h: HistoryEntity)

    @Query("DELETE FROM history WHERE rowId NOT IN (SELECT rowId FROM history ORDER BY playedAt DESC LIMIT 200)")
    suspend fun trimHistory()

    @Query("SELECT * FROM follows ORDER BY followedAt DESC")
    fun follows(): Flow<List<FollowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFollow(f: FollowEntity)

    @Query("DELETE FROM follows WHERE channelUrl = :url")
    suspend fun removeFollow(url: String)

    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun downloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :id")
    suspend fun downloadById(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addDownload(d: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :id")
    suspend fun removeDownload(id: String)

    // ---- Artwork backfill ----------------------------------------
    // Rows saved before their artwork existed (or from parse paths
    // that carried none) show letter placeholders. These find every
    // distinct video id still missing art, and write a resolved URL
    // back into every table that holds the row.

    @Query(
        "SELECT videoId FROM history WHERE thumbUrl IS NULL OR thumbUrl = '' " +
            "UNION SELECT videoId FROM favorites WHERE thumbUrl IS NULL OR thumbUrl = '' " +
            "UNION SELECT videoId FROM playlist_tracks WHERE thumbUrl IS NULL OR thumbUrl = '' " +
            "UNION SELECT videoId FROM downloads WHERE thumbUrl IS NULL OR thumbUrl = ''"
    )
    suspend fun missingArtworkIds(): List<String>

    @Query("UPDATE history SET thumbUrl = :url WHERE videoId = :id AND (thumbUrl IS NULL OR thumbUrl = '')")
    suspend fun fillHistoryThumb(id: String, url: String)

    @Query("UPDATE favorites SET thumbUrl = :url WHERE videoId = :id AND (thumbUrl IS NULL OR thumbUrl = '')")
    suspend fun fillFavoriteThumb(id: String, url: String)

    @Query("UPDATE playlist_tracks SET thumbUrl = :url WHERE videoId = :id AND (thumbUrl IS NULL OR thumbUrl = '')")
    suspend fun fillPlaylistThumb(id: String, url: String)

    @Query("UPDATE downloads SET thumbUrl = :url WHERE videoId = :id AND (thumbUrl IS NULL OR thumbUrl = '')")
    suspend fun fillDownloadThumb(id: String, url: String)
}

@Database(
    entities = [
        FavoriteEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class,
        HistoryEntity::class, FollowEntity::class, DownloadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ForteDb : RoomDatabase() {
    abstract fun dao(): ForteDao

    companion object {
        @Volatile
        private var instance: ForteDb? = null

        fun get(context: Context): ForteDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ForteDb::class.java, "forte.db"
            ).build().also { instance = it }
        }
    }
}
