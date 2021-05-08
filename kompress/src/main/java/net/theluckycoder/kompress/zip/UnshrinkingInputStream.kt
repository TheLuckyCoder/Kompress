package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.compressor.LZWInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Input stream that decompresses ZIP method 1 (unshrinking). A variation of the LZW algorithm, with some twists.
 */
internal class UnshrinkingInputStream(inputStream: InputStream) : LZWInputStream(inputStream, ByteOrder.LITTLE_ENDIAN) {

    private val isUsed: BooleanArray

    init {
        clearCode = DEFAULT_CODE_SIZE
        initializeTables(MAX_CODE_SIZE)
        isUsed = BooleanArray(getPrefixesLength())

        for (i in 0 until (1 shl 8))
            isUsed[i] = true

        tableSize = clearCode + 1
    }

    override fun addEntry(previousCode: Int, character: Byte): Int {
        var localTableSize = tableSize

        while (localTableSize < MAX_TABLE_SIZE && isUsed[localTableSize])
            localTableSize++

        tableSize = localTableSize
        val idx = addEntry(previousCode, character, MAX_TABLE_SIZE)
        if (idx >= 0)
            isUsed[idx] = true

        return idx
    }

    private fun partialClear() {
        val isParent = BooleanArray(MAX_TABLE_SIZE)

        for (i in isUsed.indices) {
            if (isUsed[i] && getPrefix(i) != UNUSED_PREFIX) {
                isParent[getPrefix(i)] = true
            }
        }

        for (i in clearCode + 1 until isParent.size) {
            if (!isParent[i]) {
                isUsed[i] = false
                setPrefix(i, UNUSED_PREFIX)
            }
        }
    }

    @Throws(IOException::class)
    override fun decompressNextSymbol(): Int {
        //
        //                   table entry    table entry
        //                  _____________   _____
        //    table entry  /             \ /     \
        //    ____________/               \       \
        //   /           / \             / \       \
        //  +---+---+---+---+---+---+---+---+---+---+
        //  | . | . | . | . | . | . | . | . | . | . |
        //  +---+---+---+---+---+---+---+---+---+---+
        //  |<--------->|<------------->|<----->|<->|
        //     symbol        symbol      symbol  symbol
        //
        val code = readNextCode()
        return when {
            code < 0 -> -1
            code == clearCode -> {
                val subCode = readNextCode()
                when {
                    subCode < 0 -> throw IOException("Unexpected EOF;")
                    subCode == 1 -> {
                        if (codeSize < MAX_CODE_SIZE) {
                            incrementCodeSize()
                        } else {
                            throw IOException("Attempt to increase code size beyond maximum")
                        }
                    }
                    subCode == 2 -> {
                        partialClear()
                        tableSize = clearCode + 1
                    }
                    else -> {
                        throw IOException("Invalid clear code subcode $subCode")
                    }
                }
                0
            }
            else -> {
                var addedUnfinishedEntry = false
                var effectiveCode = code
                if (!isUsed[code]) {
                    effectiveCode = addRepeatOfPreviousCode()
                    addedUnfinishedEntry = true
                }
                expandCodeToOutputStack(effectiveCode, addedUnfinishedEntry)
            }
        }
    }

    companion object {
        private const val MAX_CODE_SIZE = 13
        private const val MAX_TABLE_SIZE = 1 shl MAX_CODE_SIZE
    }
}
