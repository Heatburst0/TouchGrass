package com.example.touchgrass.features.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Fires at a session's scheduled end. Finalizes the session (records it, credits
 *  any focus goal, updates the notification) so completion is captured even when
 *  the app isn't open. */
@AndroidEntryPoint
class FocusEndReceiver : BroadcastReceiver() {

    @Inject
    lateinit var manager: FocusSessionManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        manager.settleIfComplete { pending.finish() }
    }
}
