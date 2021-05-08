package net.theluckycoder.kompress.zip

import java.util.zip.ZipException

/**
 * Controls details of parsing zip extra fields.
 */
public interface ExtraFieldParsingBehavior : UnparseableExtraFieldBehavior {

    /**
     * Creates an instance of ZipExtraField for the given id.
     *
     *
     * A good default implementation would be [ExtraFieldUtils.createExtraField].
     *
     * @param headerId the id for the extra field
     * @return an instance of ZipExtraField
     * @throws ZipException if an error occurs
     * @throws InstantiationException if unable to instantiate the class
     * @throws IllegalAccessException if not allowed to instantiate the class
     */
    @Throws(ZipException::class, InstantiationException::class, IllegalAccessException::class)
    public fun createExtraField(headerId: ZipShort): ZipExtraField

    /**
     * Fills in the extra field data for a single extra field.
     *
     *
     * A good default implementation would be [ExtraFieldUtils.fillExtraField].
     *
     * @param field the extra field instance to fill
     * @param data the array of extra field data
     * @param offset offset into data where this field's data starts
     * @param length the length of this field's data
     * @param local whether the extra field data stems from the local
     * file header. If this is false then the data is part if the
     * central directory header extra data.
     * @return the filled field. Usually this is the same as `field` but it could be a replacement extra field as well
     * @throws ZipException if an error occurs
     */
    @Throws(ZipException::class)
    public fun fill(
        field: ZipExtraField,
        data: ByteArray,
        offset: Int,
        length: Int,
        local: Boolean
    ): ZipExtraField
}
