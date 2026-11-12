package app.aaps.plugins.aps.advisor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the analyses in one text file. The file is written to a new file first and then moved, so a stop in the
 * middle of a write does not damage the old one.
 *
 * Put the file in a folder that the Android backup does not copy: the answers are about the health of the user.
 */
class AdvisorHistoryFile(private val file: File) : AdvisorHistoryStore {

    @Synchronized
    override fun load(): List<AdvisorHistoryEntry> = try {
        if (file.exists()) AdvisorHistoryJson.decode(file.readText()) else emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    @Synchronized
    override fun add(entry: AdvisorHistoryEntry) = write(AdvisorHistory.withNew(load(), entry))

    @Synchronized
    override fun update(entry: AdvisorHistoryEntry) {
        val entries = load()
        if (entries.none { it.timeMillis == entry.timeMillis }) return
        write(entries.map { if (it.timeMillis == entry.timeMillis) entry else it })
    }

    private fun write(entries: List<AdvisorHistoryEntry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(AdvisorHistoryJson.encode(entries))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }
}
