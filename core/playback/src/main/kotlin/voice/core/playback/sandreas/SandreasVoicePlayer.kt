package voice.core.playback.sandreas

import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.playback.misc.VolumeGain
import voice.core.playback.player.VoicePlayer
import voice.core.playback.session.MediaItemProvider
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.Chapter
import voice.core.logging.core.Logger
import voice.core.playback.session.MediaId
import voice.core.playback.session.toMediaIdOrNull
import kotlin.math.min
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

@Inject
class SandreasVoicePlayer(
                          private val player: Player,
                          private val repo: BookRepository,
                          @CurrentBookStore
                          private val currentBookStoreId: DataStore<BookId?>,
                          @SeekTimeStore
                          private val seekTimeStore: DataStore<Int>,
                          @AutoRewindAmountStore
                          private val autoRewindAmountStore: DataStore<Int>,
                          private val mediaItemProvider: MediaItemProvider,
                          private val scope: CoroutineScope,
                          private val chapterRepo: ChapterRepo,
                          private val volumeGain: VolumeGain,
)  : VoicePlayer(player, repo, currentBookStoreId, seekTimeStore, autoRewindAmountStore, mediaItemProvider, scope, chapterRepo, volumeGain) {

  private val THRESHOLD_FOR_BACK_SEEK_MS = 3000

  val seekPlayBufferTime = 850.milliseconds
  var seekJob : Job? = null
  var isSeeking = false

  private fun cancelSeekJob() {
    seekJob?.cancel()
    isSeeking = false
  }

  override fun play() {
    cancelSeekJob()
    playWithoutCancel()
  }
  fun playWithoutCancel() {
    super.play()
  }

  private suspend fun MediaItem.chapter(): Chapter? {
    val mediaId = mediaId.toMediaIdOrNull() ?: return null
    if (mediaId !is MediaId.Chapter) return null
    return chapterRepo.get(mediaId.chapterId)
  }

  fun forceSeekToNext(maxOffset: Duration=0.milliseconds) {
    cancelSeekJob()
    scope.launch {
      val currentMediaItem = player.currentMediaItem ?: return@launch
      val marks = currentMediaItem.chapter()?.chapterMarks ?: return@launch
      val currentMarkIndex = marks.indexOfFirst { mark ->
        player.currentPosition in mark.startMs..mark.endMs
      }
      val nextMark = marks.getOrNull(currentMarkIndex + 1)
      if (nextMark != null) {
        val seekToPosition = if(maxOffset == 0.milliseconds) nextMark.startMs else min(nextMark.startMs, currentPosition + maxOffset.inWholeMilliseconds)
        Logger.d("theseeker: seekToPosition=$seekToPosition / nextMark.startMs=$nextMark.startMs")
        player.seekTo(seekToPosition)
      } else {
        player.seekToNext()
      }
    }
  }

  fun forceSeekToPrevious(maxOffset: Duration = 0.milliseconds) {
    cancelSeekJob()
    scope.launch {
      val currentMediaItem = player.currentMediaItem ?: return@launch
      val marks = currentMediaItem.chapter()?.chapterMarks ?: return@launch
      val currentPosition = player.currentPosition
      val currentMark = marks.firstOrNull { mark ->
        currentPosition in mark.startMs..mark.endMs
      } ?: marks.last()

      if (currentPosition - currentMark.startMs > THRESHOLD_FOR_BACK_SEEK_MS) {
        val seekToPosition = if(maxOffset <= 0.milliseconds) currentMark.startMs else max(currentMark.startMs, currentPosition - maxOffset.inWholeMilliseconds)
        Logger.d("theseeker-prev: seekToPosition=$seekToPosition / currentMark.startMs=$currentMark.startMs")
        player.seekTo(seekToPosition)
      } else {
        val currentMarkIndex = marks.indexOf(currentMark)
        val previousMark = marks.getOrNull(currentMarkIndex - 1)
        if (previousMark != null) {
          val seekToPosition = if(maxOffset <= 0.milliseconds) previousMark.startMs else max(previousMark.startMs, currentPosition - maxOffset.inWholeMilliseconds)
          Logger.d("theseeker-prev: seekToPosition=$seekToPosition / previousMark.startMs=$previousMark.startMs")
          player.seekTo(seekToPosition)
        } else {
          Logger.d("theseeker-prev: else")

          val currentMediaItemIndex = player.currentMediaItemIndex
          if (currentMediaItemIndex > 0) {
            val previousMediaItemIndex = currentMediaItemIndex - 1
            val previousMediaItemMarks = player.getMediaItemAt(previousMediaItemIndex).chapter()?.chapterMarks
              ?: return@launch

            val lastPreviousMediaItemMark = previousMediaItemMarks.last()
            val played = currentPosition - lastPreviousMediaItemMark.endMs
            val maxOffsetRemaining = maxOffset - played.milliseconds
            val normalizedOffset = lastPreviousMediaItemMark.endMs - maxOffsetRemaining.inWholeMilliseconds
            Logger.d("theseeker-prev: played=$played, maxOffsetRemaining=$maxOffsetRemaining, rest: $normalizedOffset")
            val seekToPosition = if(maxOffset <= 0.milliseconds) lastPreviousMediaItemMark.startMs else max(lastPreviousMediaItemMark.startMs, normalizedOffset)

            player.seekTo(previousMediaItemIndex, seekToPosition)
          } else {
            player.seekTo(0)
          }
        }
      }
    }
  }

  override fun seekBack() {
    cancelSeekJob()
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds
      suspendSeekBack(skipAmount)
    }
  }

  fun seekBack(skipAmount: Duration) {
    cancelSeekJob()
    scope.launch {
      suspendSeekBack(skipAmount)
    }
  }

  suspend fun suspendSeekBack(skipAmount: Duration) {
    val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return

    val newPosition = currentPosition - skipAmount
    if (newPosition < ZERO) {
      val previousMediaItemIndex = previousMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
      if (previousMediaItemIndex == null) {
        player.seekTo(0)
      } else {
        val previousMediaItem = player.getMediaItemAt(previousMediaItemIndex)
        val chapter = previousMediaItem.chapter() ?: return
        val previousMediaItemDuration = chapter.duration.milliseconds
        player.seekTo(previousMediaItemIndex, (previousMediaItemDuration - newPosition.absoluteValue).inWholeMilliseconds)
      }
    } else {
      player.seekTo(newPosition.inWholeMilliseconds)
    }
  }

  override fun seekForward() {
    cancelSeekJob()
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds
      seekForward(skipAmount)
    }
  }
  fun seekForward(skipAmount: Duration) {
    val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return
    val newPosition = currentPosition + skipAmount

    val duration = player.duration.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?: return

    if (newPosition > duration) {
      val nextMediaItemIndex = nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
        ?: return
      player.seekTo(nextMediaItemIndex, (duration - newPosition).absoluteValue.inWholeMilliseconds)
    } else {
      player.seekTo(newPosition.inWholeMilliseconds)
    }
  }

  fun fastForward() {
    cancelSeekJob()
    seekJob = scope.launch {
      isSeeking = true
      val duration = player.duration.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?: return@launch
      val isPlaying = player.isPlaying
      while(player.currentPosition < duration.inWholeMilliseconds) {
        seekForward(10.seconds - seekPlayBufferTime)
        playWithoutCancel()
        delay(seekPlayBufferTime)
      }
      isSeeking = false
      if(isPlaying) {
        play()
      } else {
        pause()
      }
    }
  }

  fun rewind() {
    cancelSeekJob()
    seekJob = scope.launch {
      isSeeking = true
      val isPlaying = player.isPlaying
      while(player.currentPosition > 0) {
        suspendSeekBack(10.seconds + seekPlayBufferTime)
        playWithoutCancel()
        delay(seekPlayBufferTime)
      }
      isSeeking = false
      if(isPlaying) {
        play()
      } else {
        pause()
      }
    }
  }

  fun stepBack() {
    cancelSeekJob()
    scope.launch {
      suspendSeekBack(30.seconds)
    }
  }
}
