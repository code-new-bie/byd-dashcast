package com.byd.dashcast.phase1

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The instrument logs from the last projection that ended cleanly, kept for the log export. */
internal object InstrumentLogStore {
    const val MISSING = "还没有取回仪表日志。仪表日志在一次投屏正常结束后自动取回。\n"

    @Volatile
    private var directory: File? = null

    @JvmStatic
    fun attach(context: Context) {
        attach(File(context.filesDir, "instrument-logs"))
    }

    @JvmStatic
    fun attach(folder: File?) {
        directory = folder
    }

    @JvmStatic
    @Throws(IOException::class)
    fun save(name: String, text: String) {
        val folder = directory ?: return
        Files.createDirectories(folder.toPath())
        val target = File(folder, name)
        val temporary = File(folder, "$name.tmp")
        val stamped = "# fetched at " + System.currentTimeMillis() + "\n" + text
        Files.write(temporary.toPath(), stamped.toByteArray(Charsets.UTF_8))
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** The saved log, or a line saying why there is none, so the export always explains itself. */
    @JvmStatic
    fun read(name: String): String {
        val folder = directory ?: return MISSING
        return try {
            String(Files.readAllBytes(File(folder, name).toPath()), Charsets.UTF_8)
        } catch (error: IOException) {
            MISSING
        }
    }
}
