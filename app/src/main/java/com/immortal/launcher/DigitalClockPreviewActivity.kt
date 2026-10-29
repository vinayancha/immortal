/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen digital clock with the current settings. Two jobs:
 *  - the "Preview" button in Clock settings (tap anywhere to exit), and
 *  - the continuation screensaver: when the digital-clock screensaver is on and the system
 *    force-wakes the dream (see [DreamPolicy]), this takes over so the clock keeps showing
 *    instead of the photo frame. In that mode it follows the same keep-screen-on, keyguard,
 *    anti-burn-in and "open when dismissed" behaviour as [PhotoFramePreviewActivity].
 */
class DigitalClockPreviewActivity : ComponentActivity() {
  private val handler = Handler(Looper.getMainLooper())
  private var controller: DigitalClockView.Controller? = null
  private var settings = DigitalClockConfig.Settings()
  private var continuation = false
  private var launchDismissOnExit = false
  private var powerReceiver: BroadcastReceiver? = null
  private var downX = 0f
  private var downY = 0f

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive fullscreen
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    readIntent(intent)

    if (continuation) {
      showOverKeyguard()
      applyKeepScreenOn()
      SleepScheduler.onScreensaverStarted(this)
      if (DreamPolicy.hasBattery(this)) {
        powerReceiver =
            object : BroadcastReceiver() {
              override fun onReceive(c: Context, intent: Intent) {
                applyKeepScreenOn()
              }
            }
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
              addAction(Intent.ACTION_POWER_CONNECTED)
              addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
      }
    } else {
      // Keep screen on while previewing
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    settings = DigitalClockConfig.load(this)
    controller = DigitalClockView.build(this, settings)
    setContentView(controller!!.root)
    tick.run()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readIntent(intent)
  }

  private fun readIntent(intent: Intent?) {
    continuation = continuation || intent?.getBooleanExtra(EXTRA_CONTINUATION, false) == true
    launchDismissOnExit =
        intent?.getBooleanExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false) == true
  }

  // Tap (not a drag) exits — same threshold as DigitalClockDreamService.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
      }
      MotionEvent.ACTION_UP -> {
        if (kotlin.math.abs(ev.x - downX) < 48 && kotlin.math.abs(ev.y - downY) < 48) {
          Log.i(TAG, "tap -> finish(), continuation=$continuation launchDismiss=$launchDismissOnExit")
          if (continuation) PresenceHub.onInteraction(this)
          if (launchDismissOnExit) ScreensaverDismiss.launchChosenApp(this)
          finish()
        }
      }
    }
    return true
  }

  private fun showOverKeyguard() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
              WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
  }

  private fun applyKeepScreenOn() {
    val cfg = ScreensaverConfig.load(this)
    val keep =
        DreamPolicy.holdScreenOn(
            mode = cfg.presenceMode,
            hasBattery = DreamPolicy.hasBattery(this),
            batterySaver = cfg.batterySaver,
            powered = DreamPolicy.isPowered(this),
        )
    if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun onDestroy() {
    powerReceiver?.let { runCatching { unregisterReceiver(it) } }
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  private val tick = object : Runnable {
    override fun run() {
      controller?.let {
        DigitalClockView.update(it, settings)
        if (continuation) {
          // Anti-burn-in drift, as in DigitalClockDreamService: this can stay up for hours.
          val shift = AntiBurnIn.shift(System.currentTimeMillis(), 14f)
          it.root.translationX = shift.x
          it.root.translationY = shift.y
        }
      }
      handler.postDelayed(this, if (settings.showSeconds) 500L else 1_000L)
    }
  }

  companion object {
    private const val TAG = "ImmortalClockFrame"

    /** Set by [DreamPolicy] when this is the screensaver continuation, not a settings preview. */
    const val EXTRA_CONTINUATION = "continuation"
  }
}
