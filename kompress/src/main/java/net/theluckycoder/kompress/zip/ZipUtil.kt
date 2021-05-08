package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipLong.Utils.getBytes
import net.theluckycoder.kompress.zip.ZipLong.Utils.putLong
import java.io.IOException
import java.math.BigInteger
import java.util.*
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * Utility class for handling DOS and Java time conversions.
 */
internal object ZipUtil {
    /**
     * Smallest date/time ZIP can handle.
     */
    private val DOS_TIME_MIN = getBytes(0x00002100L)

    fun toDosTime(calendar: Calendar, t: Long, buf: ByteArray, offset: Int) {
        calendar.timeInMillis = t
        val year = calendar[Calendar.YEAR]
        if (year < 1980) {
            // stop callers from changing the array
            DOS_TIME_MIN.copyInto(buf, destinationOffset = offset)
            return
        }
        val month = calendar[Calendar.MONTH] + 1
        val value = (year - 1980 shl 25
                or (month shl 21)
                or (calendar[Calendar.DAY_OF_MONTH] shl 16)
                or (calendar[Calendar.HOUR_OF_DAY] shl 11)
                or (calendar[Calendar.MINUTE] shl 5)
                or (calendar[Calendar.SECOND] shr 1)).toLong()
        putLong(value, buf, offset)
    }

    /**
     * Assumes a negative integer really is a positive integer that
     * has wrapped around and re-creates the original value.
     *
     * @param i the value to treat as unsigned int.
     * @return the unsigned int as a long.
     */
    fun adjustToLong(i: Int): Long {
        return if (i < 0) {
            2 * Int.MAX_VALUE.toLong() + 2 + i
        } else i.toLong()
    }

    /**
     * Reverses a byte[] array.  Reverses in-place (thus provided array is
     * mutated), but also returns same for convenience.
     *
     * @param array to reverse (mutated in-place, but also returned for
     * convenience).
     *
     * @return the reversed array (mutated in-place, but also returned for
     * convenience).
     */
    @JvmStatic
    fun reverse(array: ByteArray): ByteArray {
        val z = array.size - 1 // position of last element
        for (i in 0 until array.size / 2) {
            val x = array[i]
            array[i] = array[z - i]
            array[z - i] = x
        }
        return array
    }

    /**
     * Converts a BigInteger into a long, and blows up
     * (NumberFormatException) if the BigInteger is too big.
     *
     * @param big BigInteger to convert.
     * @return long representation of the BigInteger.
     */
    @JvmStatic
    fun bigToLong(big: BigInteger): Long {
        if (big.bitLength() <= 63) { // bitLength() doesn't count the sign bit.
            return big.toLong()
        }
        throw NumberFormatException("The BigInteger cannot fit inside a 64 bit java long: [$big]")
    }

    /**
     *
     *
     * Converts a long into a BigInteger.  Negative numbers between -1 and
     * -2^31 are treated as unsigned 32 bit (e.g., positive) integers.
     * Negative numbers below -2^31 cause an IllegalArgumentException
     * to be thrown.
     *
     *
     * @param l long to convert to BigInteger.
     * @return BigInteger representation of the provided long.
     */
    @JvmStatic
    fun longToBig(l: Long): BigInteger {
        var value = l
        // If someone passes in a -2, they probably mean 4294967294
        // (For example, Unix UID/GID's are 32 bit unsigned.)
        require(value >= Int.MIN_VALUE) { "Negative longs < -2^31 not permitted: [$value]" }
        if (value < 0 && value >= Int.MIN_VALUE) {
            // If someone passes in a -2, they probably mean 4294967294
            // (For example, Unix UID/GID's are 32 bit unsigned.)
            value = adjustToLong(value.toInt())
        }
        return BigInteger.valueOf(value)
    }

    /**
     * Converts a signed byte into an unsigned integer representation
     * (e.g., -1 becomes 255).
     *
     * @param b byte to convert to int
     * @return int representation of the provided byte
     */
    @JvmStatic
    fun signedByteToUnsignedInt(b: Byte): Int {
        return if (b >= 0) {
            b.toInt()
        } else 256 + b
    }

    /**
     * Converts an unsigned integer to a signed byte (e.g., 255 becomes -1).
     *
     * @param i integer to convert to byte
     * @return byte representation of the provided int
     * @throws IllegalArgumentException if the provided integer is not inside the range [0,255].
     */
    @JvmStatic
    fun unsignedIntToSignedByte(i: Int): Byte {
        require(!(i > 255 || i < 0)) { "Can only convert non-negative integers between [0,255] to byte: [$i]" }
        return if (i < 128) {
            i.toByte()
        } else (i - 256).toByte()
    }

    /**
     * Converts DOS time to Java time (number of milliseconds since
     * epoch).
     * @param dosTime time to convert
     * @return converted time
     */
    fun dosToJavaTime(dosTime: Long): Long {
        val cal = Calendar.getInstance()
        // CheckStyle:MagicNumberCheck OFF - no point
        cal[Calendar.YEAR] = (dosTime shr 25 and 0x7f).toInt() + 1980
        cal[Calendar.MONTH] = (dosTime shr 21 and 0x0f).toInt() - 1
        cal[Calendar.DATE] = (dosTime shr 16).toInt() and 0x1f
        cal[Calendar.HOUR_OF_DAY] = (dosTime shr 11).toInt() and 0x1f
        cal[Calendar.MINUTE] = (dosTime shr 5).toInt() and 0x3f
        cal[Calendar.SECOND] = (dosTime shl 1).toInt() and 0x3e
        cal[Calendar.MILLISECOND] = 0
        // CheckStyle:MagicNumberCheck ON
        return cal.time.time
    }

    /**
     * If the entry has Unicode*ExtraFields and the CRCs of the
     * names/comments match those of the extra fields, transfer the
     * known Unicode values from the extra field.
     */
    fun setNameAndCommentFromExtraFields(
        ze: ZipArchiveEntry,
        originalNameBytes: ByteArray,
        commentBytes: ByteArray?
    ) {
        val nameCandidate = ze.getExtraField(UnicodePathExtraField.UPATH_ID)
        val name: UnicodePathExtraField? =
            if (nameCandidate is UnicodePathExtraField) nameCandidate else null
        val newName = getUnicodeStringIfOriginalMatches(name, originalNameBytes)

        if (newName != null) {
            ze.name = newName
            ze.nameSource = ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD
        }

        if (commentBytes != null && commentBytes.isNotEmpty()) {
            val cmtCandidate = ze.getExtraField(UnicodeCommentExtraField.UCOM_ID)
            val cmt = if (cmtCandidate is UnicodeCommentExtraField) cmtCandidate else null
            val newComment = getUnicodeStringIfOriginalMatches(cmt, commentBytes)
            if (newComment != null) {
                ze.comment = newComment
                ze.commentSource = ZipArchiveEntry.CommentSource.UNICODE_EXTRA_FIELD
            }
        }
    }

    /**
     * If the stored CRC matches the one of the given name, return the
     * Unicode name of the given field.
     *
     *
     * If the field is null or the CRCs don't match, return null
     * instead.
     */
    private fun getUnicodeStringIfOriginalMatches(
        field: AbstractUnicodeExtraField?,
        orig: ByteArray
    ): String? {
        if (field == null)
            return null

        val crc32 = CRC32()
        crc32.update(orig)
        val origCRC32 = crc32.value
        if (origCRC32 == field.nameCRC32) {
            try {
                return ZipEncodingHelper.UTF8_ZIP_ENCODING.decode(field.unicodeName!!)
            } catch (ex: IOException) {
                // UTF-8 unsupported?  should be impossible the
                // Unicode*ExtraField must contain some bad bytes
            }
        }
        return null
    }

    /**
     * Whether this library is able to read or write the given entry.
     */
    fun canHandleEntryData(entry: ZipArchiveEntry): Boolean =
        supportsEncryptionOf(entry) && supportsMethodOf(entry)

    /**
     * Whether this library supports the encryption used by the given
     * entry.
     *
     * @return true if the entry isn't encrypted at all
     */
    private fun supportsEncryptionOf(entry: ZipArchiveEntry): Boolean =
        !entry.generalPurposeBit.usesEncryption()

    /**
     * Whether this library supports the compression method used by
     * the given entry.
     *
     * @return true if the compression method is supported
     */
    private fun supportsMethodOf(entry: ZipArchiveEntry): Boolean =
        entry.method == ZipEntry.STORED || entry.method == ZipMethod.UNSHRINKING.code
                || entry.method == ZipMethod.IMPLODING.code || entry.method == ZipEntry.DEFLATED
                || entry.method == ZipMethod.ENHANCED_DEFLATED.code || entry.method == ZipMethod.BZIP2.code

    /**
     * Checks whether the entry requires features not (yet) supported
     * by the library and throws an exception if it does.
     */
    @Throws(UnsupportedZipFeatureException::class)
    fun checkRequestedFeatures(ze: ZipArchiveEntry) {
        if (!supportsEncryptionOf(ze)) {
            throw UnsupportedZipFeatureException(
                UnsupportedZipFeatureException.Feature.ENCRYPTION, ze
            )
        }

        if (!supportsMethodOf(ze)) {
            val m = ZipMethod.getMethodByCode(ze.method)
            throw UnsupportedZipFeatureException(m, ze)
        }
    }
}
