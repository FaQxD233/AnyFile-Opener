package com.anyfile.x.engine.utils

/** Bounds-checked little-endian reads; out-of-range reads return -1. */
object LittleEndian {

    fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        return u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    }
}
