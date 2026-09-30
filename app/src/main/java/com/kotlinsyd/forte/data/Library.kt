package com.kotlinsyd.forte.data

import android.content.Context
import com.kotlinsyd.forte.data.db.FollowEntity
import com.kotlinsyd.forte.data.db.ForteDao
import com.kotlinsyd.forte.data.db.ForteDb
import com.kotlinsyd.forte.data.db.PlaylistEntity
import com.kotlinsyd.forte.data.db.toFavorite
import com.kotlinsyd.forte.data.db.toHistory
import com.kotlinsyd.forte.data.db.toPlaylistTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The on-device library: favorites, playlists, history, follows, downloads.
 * All local, all instant — this is the user's own collection, no account. */
class Library(context: Context) {
    private val dao: ForteDao = ForteDb.get(context).dao()

    val favorites: Flow<List<Track>> = dao.favorites().map { list -> list.map { it.toTrack() } }
    val favoriteIds: Flow<Set<String>> = dao.favoriteIds().map { it.toSet() }
    val playlists: Flow<List<PlaylistEntity>> = dao.playlists()
    val history: Flow<List<Track>> = dao.history().map { list ->
        list.distinctBy { it.videoId }.map { it.toTrack() }
    }
    val follows: Flow<List<FollowEntity>> = dao.follows()
    val downloads = dao.downloads()
    /** Every recorded play (repeats included) for listening stats. */
    val historyRows = dao.historyRows()

    fun playlistTracks(playlistId: Long) = dao.playlistTracks(playlistId)
    fun playlistCount(playlistId: Long): Flow<Int> = dao.playlistTrackCountFlow(playlistId)

    suspend fun toggleFavorite(track: Track, isFav: Boolean) {
        if (isFav) dao.removeFavorite(track.id)
        else dao.addFavorite(track.toFavorite(System.currentTimeMillis()))
    }

    suspend fun createPlaylist(name: String): Long =
        dao.createPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(playlist: PlaylistEntity, newName: String) {
        dao.updatePlaylist(playlist.copy(name = newName))
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) {
        dao.clearPlaylistTracks(playlist.id)
        dao.deletePlaylist(playlist)
    }

    suspend fun addToPlaylist(playlistId: Long, track: Track) {
        val pos = dao.playlistTrackCount(playlistId)
        dao.addPlaylistTrack(track.toPlaylistTrack(playlistId, pos))
    }

    suspend fun removeFromPlaylist(rowId: Long) = dao.removePlaylistTrack(rowId)

    suspend fun recordPlay(track: Track) {
        dao.addHistory(track.toHistory(System.currentTimeMillis()))
        dao.trimHistory()
    }

    suspend fun toggleFollow(channelUrl: String, name: String, avatarUrl: String?, isFollowing: Boolean) {
        if (isFollowing) dao.removeFollow(channelUrl)
        else dao.addFollow(FollowEntity(channelUrl, name, avatarUrl, System.currentTimeMillis()))
    }

    suspend fun downloadedFile(videoId: String): String? =
        dao.downloadById(videoId)?.filePath?.takeIf { java.io.File(it).exists() }

    suspend fun registerDownload(entity: com.kotlinsyd.forte.data.db.DownloadEntity) =
        dao.addDownload(entity)

    suspend fun removeDownload(videoId: String) {
        dao.downloadById(videoId)?.let { java.io.File(it.filePath).delete() }
        dao.removeDownload(videoId)
    }

    /**
     * Backfill artwork for rows that were saved with none (the
     * letter placeholders in Listen again, favorites, playlists and
     * downloads). Each missing id is resolved once through the
     * account layer's player metadata and written into every table
     * holding that row. Capped per run and fully fenced — a failed
     * lookup simply leaves the row for the next launch.
     */
    suspend fun backfillArtwork() {
        val ids = runCatching { dao.missingArtworkIds() }.getOrDefault(emptyList())
        for (id in ids.take(30)) {
            val url = runCatching { YtAccount.trackArtwork(id) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: continue
            persistArtwork(id, url)
        }
    }

    /**
     * Write a resolved artwork URL into every stored row for
     * [videoId] — history, favorites, playlist tracks, downloads —
     * so a healed picture stays healed across restarts.
     */
    suspend fun persistArtwork(videoId: String, url: String) {
        runCatching {
            dao.fillHistoryThumb(videoId, url)
            dao.fillFavoriteThumb(videoId, url)
            dao.fillPlaylistThumb(videoId, url)
            dao.fillDownloadThumb(videoId, url)
        }
    }
}
