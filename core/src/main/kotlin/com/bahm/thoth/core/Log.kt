package com.bahm.thoth.core

/**
 * Tiny logging facade so :core stays free of android.util.Log. Call sites use the same
 * `Log.d/i/w/e(tag, msg)` shape as Android; the platform installs a [Sink] at startup
 * (Android → Logcat, desktop → stdout). Defaults to stdout so :core is usable standalone.
 */
object Log {
    interface Sink {
        fun log(level: Char, tag: String, msg: String, t: Throwable?)
    }

    @Volatile
    var sink: Sink = StdoutSink

    fun d(tag: String, msg: String) = sink.log('D', tag, msg, null)
    fun i(tag: String, msg: String) = sink.log('I', tag, msg, null)
    fun w(tag: String, msg: String) = sink.log('W', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable) = sink.log('W', tag, msg, t)
    fun e(tag: String, msg: String) = sink.log('E', tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable) = sink.log('E', tag, msg, t)

    object StdoutSink : Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            println("$level/$tag: $msg")
            t?.printStackTrace()
        }
    }
}
