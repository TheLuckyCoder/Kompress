package net.theluckycoder.kompress.compressor

import net.theluckycoder.kompress.MemoryLimitException
import net.theluckycoder.kompress.utils.BitInputStream
import net.theluckycoder.kompress.utils.InputStreamStatistics
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder
import kotlin.math.min

/**
 *
 * Generic LZW implementation. It is used internally for
 * the Z decompressor and the Unshrinking Zip file compression method,
 * but may be useful for third-party projects in implementing their own LZW variations.
 *
 * @NotThreadSafe
 */
internal abstract class LZWInputStream protected constructor(
    inputStream: InputStream,
    byteOrder: ByteOrder
) : CompressorInputStream(), InputStreamStatistics {
    private val oneByte = ByteArray(1)
    private val bitInputStream: BitInputStream = BitInputStream(inputStream, byteOrder)

    /**
     * Sets the clear code based on the code size.
     */
    protected var clearCode = -1
        protected set(value) {
            field = 1 shl value - 1
        }
    protected var codeSize = DEFAULT_CODE_SIZE
    private var previousCodeFirstChar: Byte = 0
    private var previousCode = UNUSED_PREFIX
    protected var tableSize = 0
    private lateinit var prefixes: IntArray
    private lateinit var characters: ByteArray
    private lateinit var outputStack: ByteArray
    private var outputStackLocation = 0

    @Throws(IOException::class)
    override fun close() {
        bitInputStream.close()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val ret = read(oneByte)
        return if (ret < 0) {
            ret
        } else 0xff and oneByte[0].toInt()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }
        var bytesRead = readFromStack(b, off, len)
        while (len - bytesRead > 0) {
            val result = decompressNextSymbol()
            if (result < 0) {
                if (bytesRead > 0) {
                    count(bytesRead)
                    return bytesRead
                }
                return result
            }
            bytesRead += readFromStack(b, off + bytesRead, len - bytesRead)
        }
        count(bytesRead)
        return bytesRead
    }

    override val compressedCount: Long
        get() = bitInputStream.bytesRead

    /**
     * Read the next code and expand it.
     * @return the expanded next code, negative on EOF
     * @throws IOException on error
     */
    @Throws(IOException::class)
    protected abstract fun decompressNextSymbol(): Int

    /**
     * Add a new entry to the dictionary.
     * @param previousCode the previous code
     * @param character the next character to append
     * @return the new code
     * @throws IOException on error
     */
    @Throws(IOException::class)
    protected abstract fun addEntry(previousCode: Int, character: Byte): Int

    /**
     * Initializes the arrays based on the maximum code size.
     * First checks that the estimated memory usage is below memoryLimitInKb
     *
     * @param maxCodeSize maximum code size
     * @param memoryLimitInKb maximum allowed estimated memory usage in Kb
     * @throws MemoryLimitException if estimated memory usage is greater than memoryLimitInKb
     * @throws IllegalArgumentException if `maxCodeSize` is not bigger than 0
     */
    @Throws(MemoryLimitException::class)
    protected fun initializeTables(maxCodeSize: Int, memoryLimitInKb: Int) {
        require(maxCodeSize > 0) { "maxCodeSize is $maxCodeSize, must be bigger than 0" }
        if (memoryLimitInKb > -1) {
            val maxTableSize = 1 shl maxCodeSize
            //account for potential overflow
            val memoryUsageInBytes =
                maxTableSize.toLong() * 6 //(4 (prefixes) + 1 (characters) +1 (outputStack))
            val memoryUsageInKb = memoryUsageInBytes shr 10
            if (memoryUsageInKb > memoryLimitInKb) {
                throw MemoryLimitException(memoryUsageInKb, memoryLimitInKb)
            }
        }
        initializeTables(maxCodeSize)
    }

    /**
     * Initializes the arrays based on the maximum code size.
     * @param maxCodeSize maximum code size
     * @throws IllegalArgumentException if `maxCodeSize` is not bigger than 0
     */
    protected fun initializeTables(maxCodeSize: Int) {
        require(maxCodeSize > 0) { "maxCodeSize is $maxCodeSize, must be bigger than 0" }
        val maxTableSize = 1 shl maxCodeSize
        prefixes = IntArray(maxTableSize)
        characters = ByteArray(maxTableSize)
        outputStack = ByteArray(maxTableSize)
        outputStackLocation = maxTableSize
        val max = 1 shl 8
        for (i in 0 until max) {
            prefixes[i] = -1
            characters[i] = i.toByte()
        }
    }

    /**
     * Reads the next code from the stream.
     * @return the next code
     * @throws IOException on error
     */
    @Throws(IOException::class)
    protected fun readNextCode(): Int {
        require(codeSize <= 31) { "Code size must not be bigger than 31" }
        return bitInputStream.readBits(codeSize).toInt()
    }

    /**
     * Adds a new entry if the maximum table size hasn't been exceeded
     * and returns the new index.
     * @param previousCode the previous code
     * @param character the character to append
     * @param maxTableSize the maximum table size
     * @return the new code or -1 if maxTableSize has been reached already
     */
    protected fun addEntry(previousCode: Int, character: Byte, maxTableSize: Int): Int {
        if (tableSize < maxTableSize) {
            prefixes[tableSize] = previousCode
            characters[tableSize] = character
            return tableSize++
        }
        return -1
    }

    /**
     * Add entry for repeat of previousCode we haven't added, yet.
     * @return new code for a repeat of the previous code or -1 if
     * maxTableSize has been reached already
     * @throws IOException on error
     */
    @Throws(IOException::class)
    protected fun addRepeatOfPreviousCode(): Int {
        if (previousCode == -1) {
            // can't have a repeat for the very first code
            throw IOException("The first code can't be a reference to its preceding code")
        }
        return addEntry(previousCode, previousCodeFirstChar)
    }

    /**
     * Expands the entry with index code to the output stack and may
     * create a new entry
     * @param code the code
     * @param addedUnfinishedEntry whether unfinished entries have been added
     * @return the new location of the output stack
     * @throws IOException on error
     */
    @Throws(IOException::class)
    protected fun expandCodeToOutputStack(code: Int, addedUnfinishedEntry: Boolean): Int {
        var entry = code
        while (entry >= 0) {
            outputStack[--outputStackLocation] = characters[entry]
            entry = prefixes[entry]
        }
        if (previousCode != -1 && !addedUnfinishedEntry) {
            addEntry(previousCode, outputStack[outputStackLocation])
        }
        previousCode = code
        previousCodeFirstChar = outputStack[outputStackLocation]
        return outputStackLocation
    }

    private fun readFromStack(b: ByteArray, off: Int, len: Int): Int {
        val remainingInStack = outputStack.size - outputStackLocation
        if (remainingInStack > 0) {
            val maxLength = min(remainingInStack, len)
            System.arraycopy(outputStack, outputStackLocation, b, off, maxLength)
            outputStackLocation += maxLength
            return maxLength
        }
        return 0
    }

    protected fun resetCodeSize() {
        codeSize = DEFAULT_CODE_SIZE
    }

    protected fun incrementCodeSize() {
        codeSize++
    }

    protected fun resetPreviousCode() {
        previousCode = -1
    }

    protected fun getPrefix(offset: Int): Int {
        return prefixes[offset]
    }

    protected fun setPrefix(offset: Int, value: Int) {
        prefixes[offset] = value
    }

    protected fun getPrefixesLength(): Int {
        return prefixes.size
    }

    companion object {
        const val DEFAULT_CODE_SIZE = 9
        const val UNUSED_PREFIX = -1
    }
}