package voice.core.playback.sandreas
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.logging.core.Logger
import voice.core.playback.sandreas.button.KeyDownHandler
import voice.core.playback.sandreas.button.MediaButtonHandler
import voice.core.playback.session.LibrarySessionCallback
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.search.BookSearchHandler
import voice.core.playback.session.search.BookSearchParser
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo

@Inject
class SandreasLibrarySessionCallback(
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val player: SandreasVoicePlayer,
  private val bookSearchParser: BookSearchParser,
  private val bookSearchHandler: BookSearchHandler,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val bookRepository: BookRepository,
) : LibrarySessionCallback(mediaItemProvider, scope, player, bookSearchParser, bookSearchHandler, currentBookStoreId, bookRepository) {

  private val mediaButtonHandler: MediaButtonHandler = KeyDownHandler(
    scope,
    { it ->
      if (it == true) {
        player.play()
      } else if (it == false) {
        player.pause()
      }
      player.isPlaying
    },
    { player.stop() },
  )

  private var wasPlayingBeforeSeek = false

  init {
    // configure available TAP / CLICK codes for mediaButtonHandler
    mediaButtonHandler.addClickAction(1) {
      Logger.d("custom-action: 1 click executed")
      if(wasPlayingBeforeSeek) {
        player.play()
        wasPlayingBeforeSeek = false
      } else if(player.isPlaying) {
        player.pause()
      } else {
        player.play()
      }
    }
    mediaButtonHandler.addClickAction(2) {
      Logger.d("custom-action: 2 clicks executed")
      player.forceSeekToNext(5.minutes)
      if(wasPlayingBeforeSeek) {
        player.play()
        wasPlayingBeforeSeek = false;
      }
    }
    mediaButtonHandler.addClickAction(3) {
      Logger.d("custom-action: 3 clicks executed")
      player.forceSeekToPrevious(5.minutes)
      if(wasPlayingBeforeSeek) {
        player.play()
        wasPlayingBeforeSeek = false;
      }
    }
    mediaButtonHandler.addClickAction(4) {
      Logger.d("custom-action: 4 clicks executed")
      wasPlayingBeforeSeek = player.isPlaying
      player.rewind()
    }
    mediaButtonHandler.addClickAction(5) {
      Logger.d("custom-action: 5 clicks executed")
      wasPlayingBeforeSeek = player.isPlaying
      player.fastForward()
    }

    mediaButtonHandler.addHoldAction(0) {
      Logger.d("custom-action: 0 clicks + hold executed");
      player.seekBack(10.seconds)
    }

    /*
    mediaButtonHandler.addClickAction(4) {
      Logger.d("4 clicks executed")
      wasPlayingBeforeSeek = player.isPlaying
      player.rewind()
    }
    mediaButtonHandler.addClickAction(5) {
      Logger.d("5 clicks executed")
      wasPlayingBeforeSeek = player.isPlaying
      player.fastForward()
    }
     */
    /*
    // longPress actions would also be possible
    // - longPress will run the configured action repeatedly with a delay of 850ms unless the action is marked as "progressive" (like fastForward or rewind)
    // problem:
    // - Android 13 (TIRAMISU) is delaying longPress for 1000ms, this results in a minimum delay of 1050ms (which feels laggy)
    // - longPress events don't work via Intent / in Background playback, so as soon as the app is not active, longPress no longer works
    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      mediaButtonHandler.handlerDelay = 650.milliseconds
    }
    mediaButtonHandler.addHoldAction(0) {
      Logger.d("0 clicks + hold executed");
      playerNotificationService.jumpBackward()
    }
    mediaButtonHandler.addHoldAction(1) {
      Logger.d("1 clicks + hold executed");
      playerNotificationService.seekForward(15.seconds.inWholeMilliseconds)
    }
    mediaButtonHandler.addHoldAction(2) {
      Logger.d("2 clicks + hold executed");
      // playerNotificationService.rewind()
      playerNotificationService.seekBackward(15.seconds.inWholeMilliseconds)
    }
     */
  }

  override fun onMediaButtonEvent(session: MediaSession, controllerInfo: ControllerInfo, intent: Intent): Boolean {
    if(Intent.ACTION_MEDIA_BUTTON == intent.action) {
      Logger.d("call onMediaButtonEvent")

      val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
      }
      return mediaButtonHandler.handleKeyEvent(keyEvent)
    }
    return super.onMediaButtonEvent(session, controllerInfo, intent)
  }
}
