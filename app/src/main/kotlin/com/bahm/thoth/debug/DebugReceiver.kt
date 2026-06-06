package com.bahm.thoth.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * adb-facing entry point for the headless test harness. Delegates to [DebugController],
 * which runs the (long) work on an app-scoped coroutine and writes results to files.
 */
@AndroidEntryPoint
class DebugReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: DebugController

    override fun onReceive(context: Context, intent: Intent) {
        controller.onAction(intent.action, intent.getStringExtra("q"))
    }
}
