package com.bahm.thoth

import android.app.Application
import android.util.Log as AndroidLog
import com.bahm.thoth.core.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ThothApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Route :core's logging facade to Logcat.
        Log.sink = object : Log.Sink {
            override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
                when (level) {
                    'E' -> if (t != null) AndroidLog.e(tag, msg, t) else AndroidLog.e(tag, msg)
                    'W' -> if (t != null) AndroidLog.w(tag, msg, t) else AndroidLog.w(tag, msg)
                    'I' -> AndroidLog.i(tag, msg)
                    else -> AndroidLog.d(tag, msg)
                }
            }
        }
    }
}
