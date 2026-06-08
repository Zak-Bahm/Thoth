package com.bahm.thoth.core

import java.io.File

/**
 * Where the pipeline writes diagnostic/eval files. Replaces direct use of Android's
 * `context.getExternalFilesDir(null)`. Android binds this to the app's external files dir;
 * desktop binds it to a configurable base directory.
 */
interface OutputSink {
    /** Returns (creating if needed) a subdirectory named [name] for output files. */
    fun dir(name: String): File
}
