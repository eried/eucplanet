package com.eried.eucplanet.data.sync

/**
 * When a settings backup was written, carried by the file itself.
 *
 * The app used to remember the time of its last backup in its own settings.
 * That copy could not be trusted: a reinstall brought back a weeks-old
 * settings snapshot and with it a "last backup" date for a file written
 * minutes ago. The file knows when it was written, so the date lives there,
 * as the first key, where a short read finds it without parsing the rest.
 */
object BackupStamp {

    const val KEY = "backupAt"

    /** How much of the file to read to find the stamp. */
    const val HEAD_BYTES = 256

    private val STAMP = Regex("\"$KEY\"\\s*:\\s*(\\d{10,17})")

    /** [json], an object's text, with the stamp as its first key. */
    fun stamp(json: String, atMs: Long): String {
        val open = json.indexOf('{')
        if (open < 0) return json
        val rest = json.substring(open + 1)
        val empty = rest.trimStart().startsWith("}")
        return json.substring(0, open + 1) + "\n  \"$KEY\": $atMs" + (if (empty) "\n" else ",") + rest
    }

    /** The stamp in the first bytes of a backup, or null for a file written
     *  before backups carried one. */
    fun read(head: String): Long? =
        STAMP.find(head)?.groupValues?.get(1)?.toLongOrNull()
}
