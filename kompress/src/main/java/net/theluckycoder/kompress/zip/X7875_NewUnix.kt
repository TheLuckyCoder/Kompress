package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.zip.ZipUtil.bigToLong
import net.theluckycoder.kompress.zip.ZipUtil.longToBig
import net.theluckycoder.kompress.zip.ZipUtil.reverse
import net.theluckycoder.kompress.zip.ZipUtil.signedByteToUnsignedInt
import net.theluckycoder.kompress.zip.ZipUtil.unsignedIntToSignedByte
import java.io.Serializable
import java.math.BigInteger
import java.util.zip.ZipException
import kotlin.math.max

/**
 * An extra field that stores UNIX UID/GID data (owner &amp; group ownership) for a given
 * zip entry.  We're using the field definition given in Info-Zip's source archive:
 * zip-3.0.tar.gz/proginfo/extrafld.txt
 *
 * <pre>
 * Local-header version:
 *
 * Value         Size        Description
 * -----         ----        -----------
 * 0x7875        Short       tag for this extra block type ("ux")
 * TSize         Short       total data size for this block
 * Version       1 byte      version of this extra field, currently 1
 * UIDSize       1 byte      Size of UID field
 * UID           Variable    UID for this entry (little endian)
 * GIDSize       1 byte      Size of GID field
 * GID           Variable    GID for this entry (little endian)
 *
 * Central-header version:
 *
 * Value         Size        Description
 * -----         ----        -----------
 * 0x7855        Short       tag for this extra block type ("Ux")
 * TSize         Short       total data size for this block (0)
</pre> *
 */
internal class X7875_NewUnix : ZipExtraField, Cloneable, Serializable {

    private var version = 1 // always '1' according to current info-zip spec.

    // BigInteger helps us with little-endian / big-endian conversions.
    // (thanks to BigInteger.toByteArray() and a reverse() method we created).
    // Also, the spec theoretically allows UID/GID up to 255 bytes long!
    private var uid: BigInteger = ONE_THOUSAND
    private var gid: BigInteger = ONE_THOUSAND

    /**
     * Gets the UID as a long.  UID is typically a 32 bit unsigned
     * value on most UNIX systems, so we return a long to avoid
     * integer overflow into the negatives in case values above
     * and including 2^31 are being used.
     */
    var UID: Long
        get() = bigToLong(uid)
        set(l) {
            uid = longToBig(l)
        }

    /**
     * Gets the GID as a long.  GID is typically a 32 bit unsigned
     * value on most UNIX systems, so we return a long to avoid
     * integer overflow into the negatives in case values above
     * and including 2^31 are being used.
     */
    var GID: Long
        get() = bigToLong(gid)
        set(l) {
            gid = longToBig(l)
        }

    /**
     * The Header-ID.
     */
    override val headerId: ZipShort = HEADER_ID

    /**
     * Length of the extra field in the local file data - without
     * Header-ID or length specifier.
     *
     * @return a [ZipShort] for the length of the data of this extra field
     */
    override fun getLocalFileDataLength(): ZipShort {
        var b = trimLeadingZeroesForceMinLength(uid.toByteArray())
        val uidSize = b?.size ?: 0
        b = trimLeadingZeroesForceMinLength(gid.toByteArray())
        val gidSize = b?.size ?: 0

        // The 3 comes from:  version=1 + uidsize=1 + gidsize=1
        return ZipShort(3 + uidSize + gidSize)
    }

    /**
     * Length of the extra field in the central directory data - without
     * Header-ID or length specifier.
     *
     * @return a [ZipShort] for the length of the data of this extra field
     */
    override fun getCentralDirectoryLength(): ZipShort = ZERO

    /**
     * The actual data to put into local file data - without Header-ID
     * or length specifier.
     *
     * @return get the data
     */
    override fun getLocalFileDataData(): ByteArray {
        var uidBytes = uid.toByteArray()
        var gidBytes = gid.toByteArray()

        // BigInteger might prepend a leading-zero to force a positive representation
        // (e.g., so that the sign-bit is set to zero).  We need to remove that
        // before sending the number over the wire.
        uidBytes = trimLeadingZeroesForceMinLength(uidBytes)
        val uidBytesLen = uidBytes?.size ?: 0
        gidBytes = trimLeadingZeroesForceMinLength(gidBytes)
        val gidBytesLen = gidBytes?.size ?: 0

        // Couldn't bring myself to just call getLocalFileDataLength() when we've
        // already got the arrays right here.  Yeah, yeah, I know, premature
        // optimization is the root of all...
        //
        // The 3 comes from:  version=1 + uidsize=1 + gidsize=1
        val data = ByteArray(3 + uidBytesLen + gidBytesLen)

        // reverse() switches byte array from big-endian to little-endian.
        uidBytes?.let { reverse(it) }
        gidBytes?.let { reverse(it) }
        var pos = 0
        data[pos++] = unsignedIntToSignedByte(version)
        data[pos++] = unsignedIntToSignedByte(uidBytesLen)
        if (uidBytes != null) {
            System.arraycopy(uidBytes, 0, data, pos, uidBytesLen)
        }
        pos += uidBytesLen
        data[pos++] = unsignedIntToSignedByte(gidBytesLen)
        if (gidBytes != null) {
            System.arraycopy(gidBytes, 0, data, pos, gidBytesLen)
        }
        return data
    }

    /**
     * The actual data to put into central directory data - without Header-ID
     * or length specifier.
     *
     * @return get the data
     */
    override fun getCentralDirectoryData(): ByteArray = ByteArray(0)

    /**
     * Populate data from this array as if it was in local file data.
     *
     * @param buffer   an array of bytes
     * @param offset the start offset
     * @param length the number of bytes in the array from offset
     * @throws ZipException on error
     */
    @Throws(ZipException::class)
    override fun parseFromLocalFileData(buffer: ByteArray, offset: Int, length: Int) {
        var newOffset = offset
        reset()
        if (length < 3)
            throw ZipException("X7875_NewUnix length is too short, only $length bytes")

        version = signedByteToUnsignedInt(buffer[newOffset++])
        val uidSize = signedByteToUnsignedInt(buffer[newOffset++])
        if (uidSize + 3 > length)
            throw ZipException("X7875_NewUnix invalid: uidSize $uidSize doesn't fit into $length bytes")

        val uidBytes = buffer.copyOfRange(newOffset, newOffset + uidSize)
        newOffset += uidSize
        uid = BigInteger(1, reverse(uidBytes)) // sign-bit forced positive

        val gidSize = signedByteToUnsignedInt(buffer[newOffset++])
        if (uidSize + 3 + gidSize > length)
            throw ZipException("X7875_NewUnix invalid: gidSize $gidSize doesn't fit into $length bytes")

        val gidBytes = buffer.copyOfRange(newOffset, newOffset + gidSize)
        gid = BigInteger(1, reverse(gidBytes)) // sign-bit forced positive
    }

    /**
     * Doesn't do anything since this class doesn't store anything
     * inside the central directory.
     */
    override fun parseFromCentralDirectoryData(buffer: ByteArray, offset: Int, length: Int) = Unit

    /**
     * Reset state back to newly constructed state.  Helps us make sure
     * parse() calls always generate clean results.
     */
    private fun reset() {
        // Typical UID/GID of the first non-root user created on a unix system.
        uid = ONE_THOUSAND
        gid = ONE_THOUSAND
    }

    /**
     * Returns a String representation of this class useful for
     * debugging purposes.
     *
     * @return A String representation of this class useful for
     * debugging purposes.
     */
    override fun toString(): String = "0x7875 Zip Extra Field: UID=$uid GID=$gid"

    @Throws(CloneNotSupportedException::class)
    public override fun clone(): Any {
        return super.clone()
    }

    override fun equals(other: Any?): Boolean {
        if (other is X7875_NewUnix) {
            // We assume uid and gid can never be null.
            return version == other.version && uid == other.uid && gid == other.gid
        }
        return false
    }

    override fun hashCode(): Int {
        var hc = -1234567 * version
        // Since most UID's and GID's are below 65,536, this is (hopefully!)
        // a nice way to make sure typical UID and GID values impact the hash
        // as much as possible.
        hc = hc xor Integer.rotateLeft(uid.hashCode(), 16)
        hc = hc xor gid.hashCode()
        return hc
    }

    companion object {
        private val HEADER_ID = ZipShort(0x7875)
        private val ZERO = ZipShort(0)
        private val ONE_THOUSAND = BigInteger.valueOf(1000)
        private const val serialVersionUID = 1L

        /**
         * Not really for external usage, but marked "package" visibility
         * to help us JUnit it.   Trims a byte array of leading zeroes while
         * also enforcing a minimum length, and thus it really trims AND pads
         * at the same time.
         *
         * @param array byte[] array to trim & pad.
         * @return trimmed & padded byte[] array.
         */
        fun trimLeadingZeroesForceMinLength(array: ByteArray?): ByteArray? {
            if (array == null)
                return null

            var pos = 0
            for (b in array) {
                if (b.toInt() == 0) {
                    pos++
                } else {
                    break
                }
            }

            /*
            I agonized over my choice of MIN_LENGTH=1.  Here's the situation:
            InfoZip (the tool I am using to test interop) always sets these
            to length=4.  And so a UID of 0 (typically root) for example is
            encoded as {4,0,0,0,0} (len=4, 32 bits of zero), when it could just
            as easily be encoded as {1,0} (len=1, 8 bits of zero) according to
            the spec.

            In the end I decided on MIN_LENGTH=1 for four reasons:

            1.)  We are adhering to the spec as far as I can tell, and so
                 a consumer that cannot parse this is broken.

            2.)  Fundamentally, zip files are about shrinking things, so
                 let's save a few bytes per entry while we can.

            3.)  Of all the people creating zip files using commons-
                 compress, how many care about UNIX UID/GID attributes
                 of the files they store?   (e.g., I am probably thinking
                 way too hard about this and no one cares!)

            4.)  InfoZip's tool, even though it carefully stores every UID/GID
                 for every file zipped on a unix machine (by default) currently
                 appears unable to ever restore UID/GID.
                 unzip -X has no effect on my machine, even when run as root!!!!

            And thus it is decided:  MIN_LENGTH=1.

            If anyone runs into interop problems from this, feel free to set
            it to MIN_LENGTH=4 at some future time, and then we will behave
            exactly like InfoZip (requires changes to unit tests, though).

            And I am sorry that the time you spent reading this comment is now
            gone and you can never have it back.

            */
            val minLength = 1
            val trimmedArray = ByteArray(max(minLength, array.size - pos))
            val startPos = trimmedArray.size - (array.size - pos)
            System.arraycopy(array, pos, trimmedArray, startPos, trimmedArray.size - startPos)
            return trimmedArray
        }
    }
}
