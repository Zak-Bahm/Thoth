package com.bahm.thoth.desktop

import com.bahm.thoth.core.OutputSink
import java.io.File

/** Desktop [OutputSink]: writes perf/debug/eval files under a configurable base directory. */
class DesktopOutputSink(private val base: File) : OutputSink {
    override fun dir(name: String): File {
        val dir = File(base, name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
