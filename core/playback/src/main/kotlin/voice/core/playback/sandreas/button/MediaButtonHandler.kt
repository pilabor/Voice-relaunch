package voice.core.playback.sandreas.button

import android.view.KeyEvent
import kotlin.time.Duration

interface MediaButtonHandler {
  var handlerDelay: Duration

  fun handleKeyEvent(keyEvent: KeyEvent?): Boolean
  fun addClickAction(clicks: Int, callback: () -> Unit)
  fun addHoldAction(clicksBeforeHold: Int, callback: () -> Unit)
}
