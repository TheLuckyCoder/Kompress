package net.theluckycoder.kompress.utils

private const val MAX_SANITIZED_NAME_LENGTH = 255

/**
 * Returns a "sanitized" version of the string given as arguments,
 * where sanitized means non-printable characters have been
 * replaced with a question mark and the outcome is not longer
 * than 255 chars.
 *
 *
 * This method is used to clean up file names when they are
 * used in exception messages as they may end up in log files or
 * as console output and may have been read from a corrupted
 * input.
 *
 * @return a sanitized version of the argument
 */
internal fun String.sanitize(): String {
    val charArray = toCharArray()
    val chars =
        if (charArray.size <= MAX_SANITIZED_NAME_LENGTH) charArray else charArray.copyOf(MAX_SANITIZED_NAME_LENGTH)
    if (charArray.size > MAX_SANITIZED_NAME_LENGTH) {
        for (i in MAX_SANITIZED_NAME_LENGTH - 3 until MAX_SANITIZED_NAME_LENGTH)
            chars[i] = '.'
    }

    return buildString {
        for (c in chars) {
            if (!Character.isISOControl(c)) {
                val block = Character.UnicodeBlock.of(c)
                if (block != null && block !== Character.UnicodeBlock.SPECIALS) {
                    append(c)
                    continue
                }
            }
            append('?')
        }
    }
}
