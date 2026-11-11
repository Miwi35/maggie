package com.maggie.app.media

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The session's answers (MAG-773): the tree of [DigestMediaLibrary], the signed URL asked for when a
 * play request arrives, and no playback of its own — neither on a connection nor on a resumption.
 */
class DigestSessionCallback(
    private val library: DigestMediaLibrary,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId != DigestMediaLibrary.ROOT_ID) {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }
        return future { LibraryResult.ofItemList(ImmutableList.copyOf(library.children()), params) }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        val item = if (mediaId == DigestMediaLibrary.ROOT_ID) library.root() else library.latest()?.takeIf { it.mediaId == mediaId }
        if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future {
        mediaItems.mapNotNull { library.playable(it) }.toMutableList()
    }

    /** The system asks to resume what played last (a headset button, the car's start): nothing plays by itself. */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("The digest is never resumed by itself"))

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result
    }
}
