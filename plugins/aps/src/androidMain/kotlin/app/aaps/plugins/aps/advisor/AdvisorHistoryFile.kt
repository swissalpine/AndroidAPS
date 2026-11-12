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
 *
 * @param fileOf gives the file. It is called each time the file is used and not before, so making the store does
 *   not touch the file system. The plugin is made when the app starts.
 */
class AdvisorHistoryFile(private val fileOf: () -> File) : AdvisorHistoryStore {

    @Synchronized
    override fun load(): List<AdvisorHistoryEntry> = try {
        val file = fileOf()
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
        val file = fileOf()
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(AdvisorHistoryJson.encode(entries))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun clear() {
        fileOf().delete()
    }
}
