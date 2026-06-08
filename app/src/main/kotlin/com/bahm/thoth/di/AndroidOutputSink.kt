package com.bahm.thoth.di

import android.content.Context
import com.bahm.thoth.core.OutputSink
import java.io.File

/** Android [OutputSink]: writes under the app's external files dir (as before the :core split). */
class AndroidOutputSink(private val context: Context) : OutputSink {
    override fun dir(name: String): File {
        val dir = File(context.getExternalFilesDir(null), name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
