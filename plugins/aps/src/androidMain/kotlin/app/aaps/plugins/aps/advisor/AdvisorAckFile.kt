package app.aaps.plugins.aps.advisor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the confirmed warning keys in a text file, one key in each line. It is written to a new file first and then
 * moved, like [AdvisorHistoryFile].
 *
 * @param fileOf gives the file. It is called each time the file is used and not before, so making the store does
 *   not touch the file system.
 */
class AdvisorAckFile(private val fileOf: () -> File) : AdvisorAckStore {

    @Synchronized
    override fun load(): Set<String> = try {
        val file = fileOf()
        if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    } catch (e: IOException) {
        emptySet()
    }

    @Synchronized
    override fun add(keys: Set<String>, now: Long) {
        val kept = AdvisorAcks.keep(load() + keys, now)
        val file = fileOf()
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(kept.joinToString("\n"))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun clear() {
        fileOf().delete()
    }
}
