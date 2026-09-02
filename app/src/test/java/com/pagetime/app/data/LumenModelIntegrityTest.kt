package com.pagetime.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The model file is a ZIP archive (MediaPipe `.task`). A corrupt or truncated
 * download must be detected in pure Java BEFORE the native loader sees it —
 * MediaPipe aborts the whole process on a bad file with no exception Kotlin
 * can catch, which is exactly the "crashes with no log" symptom. These tests
 * pin the structural check against real files on disk.
 */
class LumenModelIntegrityTest {

    private fun tempFile(name: String, bytes: ByteArray): File {
        val dir = Files.createTempDirectory("lumen-integrity").toFile()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    /** A structurally valid ZIP, e.g. what a real `.task` model looks like. */
    private fun validZipBytes(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("model.tflite"))
            // Repetitive content: a real tflite payload would be larger, but
            // the structure — local header, entries, central directory, EOCD —
            // is identical regardless of size.
            val payload = "weights weights weights ".repeat(2_000).toByteArray()
            zip.write(payload)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a structurally valid zip passes`() {
        assertTrue(LumenModelIntegrity.isZipIntact(tempFile("model.task", validZipBytes())))
    }

    @Test
    fun `a zip with a leading prefix passes like the real MediaPipe task file`() {
        // The real Qwen task file begins with 4 zero bytes BEFORE the ZIP
        // payload, and the archive's own offsets account for them (the local
        // header sits at offset 4). Java's ZipOutputStream records offsets
        // relative to its own start, so it can't emit that shape directly —
        // build a normal zip, then shift every absolute offset by the prefix
        // length, byte-for-byte what the genuine task file looks like.
        val plain = validZipBytes()
        val prefix = 4
        val eocd = plain.size - 22
        val shifted = ByteArray(plain.size + prefix)
        System.arraycopy(plain, 0, shifted, prefix, plain.size)
        // EOCD cdOffset field (at +16) moves with the archive.
        val cdOffset = readLeInt(shifted, eocd + prefix + 16)
        writeLeInt(shifted, eocd + prefix + 16, cdOffset + prefix)
        // Each central-directory record's local-header offset (at +42) also
        // moves; the records themselves sit at cdOffset + prefix now.
        val entries = readLeShort(shifted, eocd + prefix + 10)
        var p = cdOffset + prefix
        repeat(entries) {
            val headerOffset = readLeInt(shifted, p + 42)
            writeLeInt(shifted, p + 42, headerOffset + prefix)
            val nameLen = readLeShort(shifted, p + 28)
            val extraLen = readLeShort(shifted, p + 30)
            val commentLen = readLeShort(shifted, p + 32)
            p += 46 + nameLen + extraLen + commentLen
        }
        assertTrue(LumenModelIntegrity.isZipIntact(tempFile("model.task", shifted)))
    }

    private fun readLeInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or
            ((b[offset + 1].toInt() and 0xff) shl 8) or
            ((b[offset + 2].toInt() and 0xff) shl 16) or
            ((b[offset + 3].toInt() and 0xff) shl 24)

    private fun readLeShort(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8)

    private fun writeLeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value shr 8) and 0xff).toByte()
        b[offset + 2] = ((value shr 16) and 0xff).toByte()
        b[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    @Test
    fun `a truncated zip fails`() {
        val full = validZipBytes()
        val cut = full.copyOf(full.size / 2) // local header survives, EOCD gone
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", cut)))
    }

    @Test
    fun `a zip missing its end-of-central-directory fails`() {
        val full = validZipBytes()
        val cut = full.copyOf(full.size - 22) // strips the EOCD record
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", cut)))
    }

    @Test
    fun `a zip with trailing garbage appended fails`() {
        val full = validZipBytes()
        val padded = full + ByteArray(64) // EOCD no longer at end-of-file
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", padded)))
    }

    @Test
    fun `a zip with a corrupted central directory fails`() {
        val full = validZipBytes()
        // EOCD (no comment) sits at size-22; the central-directory offset is
        // its little-endian field at +16. Corrupt the CD's start signature.
        val eocdPos = full.size - 22
        val cdOffset =
            (full[eocdPos + 16].toInt() and 0xff) or
                ((full[eocdPos + 17].toInt() and 0xff) shl 8) or
                ((full[eocdPos + 18].toInt() and 0xff) shl 16) or
                ((full[eocdPos + 19].toInt() and 0xff) shl 24)
        val corrupted = full.copyOf()
        corrupted[cdOffset] = 0x00 // was PK
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", corrupted)))
    }

    @Test
    fun `garbage or empty files fail`() {
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", ByteArray(0))))
        assertFalse(LumenModelIntegrity.isZipIntact(tempFile("model.task", "not a zip at all".toByteArray())))
        assertFalse(
            LumenModelIntegrity.isZipIntact(
                tempFile("model.task", ByteArray(546_660)) // right ballpark size, all zeros
            )
        )
    }

    @Test
    fun `a missing file fails`() {
        val missing = File(Files.createTempDirectory("lumen-integrity").toFile(), "nope.task")
        assertFalse(LumenModelIntegrity.isZipIntact(missing))
    }
}
