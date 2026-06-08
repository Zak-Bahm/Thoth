package com.bahm.thoth.desktop

import java.io.File
import java.nio.file.Files

/**
 * Extracts the bundled glibc x86_64 ZIM natives to a temp dir and loads them in dependency
 * order. The wrapper resolves libzim.so.9 via its `$ORIGIN` rpath (same temp dir). No
 * java.library.path or external setup required.
 */
object NativeLoader {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureZimLoaded() {
        if (loaded) return
        val tmp = Files.createTempDirectory("thoth-zim-native").toFile()
        tmp.deleteOnExit()
        // libzim.so.9 must be present (the wrapper's SONAME dependency); load it first.
        val files = listOf("libzim.so.9", "libzim_wrapper.so")
        for (name in files) {
            val res = "/native/linux-x86_64/$name"
            val stream = NativeLoader::class.java.getResourceAsStream(res)
                ?: error("Bundled native '$res' missing — run desktop/build-zim-native.sh first.")
            val out = File(tmp, name)
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out.deleteOnExit()
        }
        System.load(File(tmp, "libzim.so.9").absolutePath)
        System.load(File(tmp, "libzim_wrapper.so").absolutePath)
        loaded = true
    }
}
