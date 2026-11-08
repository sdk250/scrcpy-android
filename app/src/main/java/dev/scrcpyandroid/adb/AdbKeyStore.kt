package dev.scrcpyandroid.adb

import java.io.File

/** Stores the ADB key as `dir/adbkey` (PKCS#8 PEM) and `dir/adbkey.comment`. */
class AdbKeyStore(private val dir: File) {
    private val keyFile get() = File(dir, "adbkey")
    private val commentFile get() = File(dir, "adbkey.comment")

    @Synchronized
    fun loadOrCreate(): AdbKeyPair {
        if (keyFile.isFile) {
            try {
                val comment = if (commentFile.isFile) commentFile.readText().trim() else ""
                return AdbKeyPair.importPem(keyFile.readText(), comment.ifEmpty { DEFAULT_COMMENT })
            } catch (_: InvalidKeyFormatException) {
                // Keep the unreadable file for recovery, then create a fresh key.
                val corrupt = File(dir, "adbkey.corrupt")
                corrupt.delete()
                if (!keyFile.renameTo(corrupt)) keyFile.copyTo(corrupt, overwrite = true)
            }
        }
        val key = AdbKeyPair.generate()
        save(key)
        return key
    }

    @Synchronized
    fun save(key: AdbKeyPair) {
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Cannot create $dir")
        writeAtomically(commentFile, key.comment)
        writeAtomically(keyFile, key.exportPrivatePem())
    }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val DEFAULT_COMMENT = "scrcpy-android@android"
    }
}
