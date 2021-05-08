package net.theluckycoder.kompress.zip

import java.nio.charset.Charset

/**
 * An interface added to allow access to the character set associated with an [NioZipEncoding],
 * without requiring a new method to be added to [ZipEncoding].
 *
 *
 * This avoids introducing a
 * potentially breaking change, or making [NioZipEncoding] a public class.
 *
 */
public interface CharsetAccessor {

    /**
     * Provides access to the character set associated with an object.
     *
     *
     * This allows nio oriented code to use more natural character encoding/decoding methods,
     * whilst allowing existing code to continue to rely on special-case error handling for UTF-8.
     *
     * @return the character set associated with this object
     */
    public fun getCharset(): Charset
}
