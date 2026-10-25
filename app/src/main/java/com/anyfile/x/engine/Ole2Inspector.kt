package com.anyfile.x.engine

import com.anyfile.x.engine.MimeDetector.DetectionConfidence
import com.anyfile.x.engine.MimeDetector.DetectionResult
import com.anyfile.x.engine.MimeDetector.DetectionSource
import com.anyfile.x.engine.MimeDetector.FileType
import com.anyfile.x.engine.utils.ByteSearch
import com.anyfile.x.engine.utils.LittleEndian.u16
import com.anyfile.x.engine.utils.LittleEndian.u32

/**
 * Identifies legacy Office files (OLE2 compound documents) by the stream names in
 * their directory entries. Names are only accepted at 128-byte entry boundaries
 * with a matching length field, so document text cannot cause false matches.
 */
internal object Ole2Inspector {

    private val SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    private const val DIRECTORY_ENTRY_SIZE = 128
    private const val NAME_LENGTH_OFFSET = 64
    private const val MAX_NAME_BYTES = 64
    private const val DIRECTORY_SCAN_BYTES = 16 * 1024
    private const val LAST_REGULAR_SECTOR = 0xFFFFFFFAL

    private class Stream(
        val name: String,
        val mime: String,
        val label: String,
        val prefix: Boolean = false
    ) {
        val pattern: ByteArray = name.toByteArray(Charsets.UTF_16LE)
    }

    private val STREAMS = listOf(
        Stream("WordDocument", MimeTypes.MSWORD, "Word"),
        Stream("Workbook", MimeTypes.MS_EXCEL, "Excel"),
        Stream("Book", MimeTypes.MS_EXCEL, "Excel 5"),
        Stream("PowerPoint Document", MimeTypes.MS_POWERPOINT, "PowerPoint"),
        Stream("__substg1.0_", MimeTypes.MS_OUTLOOK, "Outlook message", prefix = true),
        Stream("VisioDocument", MimeTypes.VISIO, "Visio")
    )

    fun matches(header: ByteArray): Boolean =
        header.size >= SIGNATURE.size &&
            SIGNATURE.indices.all { (header[it].toInt() and 0xFF) == SIGNATURE[it] }

    fun inspect(header: ByteArray, extension: String, access: ContainerAccess?): DetectionResult {
        findStream(header, 0L)?.let { return it }
        readDirectory(header, access)?.let { (bytes, offset) ->
            findStream(bytes, offset)?.let { return it }
        }
        MimeTypes.ole2Flavor(extension)?.let { known ->
            return DetectionResult(
                known.fileType,
                known.mime,
                DetectionConfidence.MEDIUM,
                DetectionSource.FILE_EXTENSION,
                "OLE2 compound file with .$extension extension"
            )
        }
        return DetectionResult(
            FileType.DOCUMENT,
            MimeTypes.OLE_STORAGE,
            DetectionConfidence.MEDIUM,
            DetectionSource.MAGIC_BYTES,
            "OLE2 compound file; document type not identified"
        )
    }

    private fun findStream(bytes: ByteArray, baseOffset: Long): DetectionResult? {
        for (stream in STREAMS) {
            var index = ByteSearch.indexOf(bytes, stream.pattern)
            while (index >= 0) {
                if (isDirectoryEntryName(bytes, index, baseOffset, stream)) {
                    return DetectionResult(
                        FileType.DOCUMENT,
                        stream.mime,
                        DetectionConfidence.HIGH,
                        DetectionSource.CONTAINER_CONTENT,
                        "OLE2 directory lists \"${stream.name}\" (${stream.label})"
                    )
                }
                index = ByteSearch.indexOf(bytes, stream.pattern, from = index + 1)
            }
        }
        return null
    }

    private fun isDirectoryEntryName(
        bytes: ByteArray,
        index: Int,
        baseOffset: Long,
        stream: Stream
    ): Boolean {
        if ((baseOffset + index) % DIRECTORY_ENTRY_SIZE != 0L) return false
        // The stored length counts the terminating NUL character.
        val nameLength = u16(bytes, index + NAME_LENGTH_OFFSET)
        val exactLength = stream.pattern.size + 2
        return if (stream.prefix) {
            nameLength in exactLength..MAX_NAME_BYTES
        } else {
            nameLength == exactLength
        }
    }

    /** Reads the start of the directory stream, located from the file header. */
    private fun readDirectory(header: ByteArray, access: ContainerAccess?): Pair<ByteArray, Long>? {
        val source = access?.randomAccess() ?: return null
        val sectorShift = u16(header, 0x1E)
        if (sectorShift != 9 && sectorShift != 12) return null
        val firstSector = u32(header, 0x30)
        if (firstSector < 0 || firstSector >= LAST_REGULAR_SECTOR) return null
        val offset = (firstSector + 1) shl sectorShift
        if (offset >= source.size) return null
        val length = minOf(DIRECTORY_SCAN_BYTES.toLong(), source.size - offset).toInt()
        val bytes = source.read(offset, length) ?: return null
        return bytes to offset
    }
}
