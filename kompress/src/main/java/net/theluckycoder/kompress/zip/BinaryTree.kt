package net.theluckycoder.kompress.zip

import net.theluckycoder.kompress.utils.readFully
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.max

/**
 * Binary tree of positive values.
 *
 * @author Emmanuel Bourg
 */
internal class BinaryTree(depth: Int) {
    /**
     * The array representing the binary tree. The root is at index 0,
     * the left children are at 2*i+1 and the right children at 2*i+2.
     */
    private val tree: IntArray

    /**
     * Adds a leaf to the tree.
     *
     * @param node   the index of the node where the path is appended
     * @param path   the path to the leaf (bits are parsed from the right to the left)
     * @param depth  the number of nodes in the path
     * @param value  the value of the leaf (must be positive)
     */
    fun addLeaf(node: Int, path: Int, depth: Int, value: Int) {
        if (depth == 0) {
            // end of the path reached, add the value to the current node
            if (tree[node] == UNDEFINED) {
                tree[node] = value
            } else {
                throw IllegalArgumentException("Tree value at index " + node + " has already been assigned (" + tree[node] + ")")
            }
        } else {
            // mark the current node as a non leaf node
            tree[node] = NODE

            // move down the path recursively
            val nextChild = 2 * node + 1 + (path and 1)
            addLeaf(nextChild, path ushr 1, depth - 1, value)
        }
    }

    /**
     * Reads a value from the specified bit stream.
     *
     * @param stream
     * @return the value decoded, or -1 if the end of the stream is reached
     */
    @Throws(IOException::class)
    fun read(stream: BitStream): Int {
        var currentIndex = 0
        while (true) {
            val bit = stream.nextBit()
            if (bit == -1) {
                return -1
            }
            val childIndex = 2 * currentIndex + 1 + bit
            val value = tree[childIndex]
            currentIndex = if (value == NODE) {
                // consume the next bit
                childIndex
            } else return if (value != UNDEFINED) {
                value
            } else {
                throw IOException("The child $bit of node at index $currentIndex is not defined")
            }
        }
    }

    companion object {
        /** Value in the array indicating an undefined node  */
        private const val UNDEFINED = -1

        /** Value in the array indicating a non leaf node  */
        private const val NODE = -2

        /**
         * Decodes the packed binary tree from the specified stream.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun decode(inputStream: InputStream, totalNumberOfValues: Int): BinaryTree {
            require(totalNumberOfValues >= 0) {
                "totalNumberOfValues must be bigger than 0, is $totalNumberOfValues"
            }
            // the first byte contains the size of the structure minus one
            val size = inputStream.read() + 1
            if (size == 0) {
                throw IOException("Cannot read the size of the encoded tree, unexpected end of stream")
            }
            val encodedTree = ByteArray(size)
            val read: Int = inputStream.readFully(encodedTree)
            if (read != size) {
                throw EOFException()
            }
            /** The maximum bit length for a value (16 or lower)  */
            var maxLength = 0
            val originalBitLengths = IntArray(totalNumberOfValues)
            var pos = 0
            for (b in encodedTree) {
                // each byte encodes the number of values (upper 4 bits) for a bit length (lower 4 bits)
                val numberOfValues: Int = (b.toInt() and 0xF0 shr 4) + 1
                if (pos + numberOfValues > totalNumberOfValues) {
                    throw IOException("Number of values exceeds given total number of values")
                }
                val bitLength: Int = (b.toInt() and 0x0F) + 1
                for (j in 0 until numberOfValues) {
                    originalBitLengths[pos++] = bitLength
                }
                maxLength = max(maxLength, bitLength)
            }

            // sort the array of bit lengths and memorize the permutation used to restore the order of the codes
            val permutation = IntArray(originalBitLengths.size)
            for (k in permutation.indices) {
                permutation[k] = k
            }
            var c = 0
            val sortedBitLengths = IntArray(originalBitLengths.size)
            for (k in originalBitLengths.indices) {
                // iterate over the values
                for (l in originalBitLengths.indices) {
                    // look for the value in the original array
                    if (originalBitLengths[l] == k) {
                        // put the value at the current position in the sorted array...
                        sortedBitLengths[c] = k

                        // ...and memorize the permutation
                        permutation[c] = l
                        c++
                    }
                }
            }

            // decode the values of the tree
            var code = 0
            var codeIncrement = 0
            var lastBitLength = 0
            val codes = IntArray(totalNumberOfValues)
            for (i in totalNumberOfValues - 1 downTo 0) {
                code += codeIncrement
                if (sortedBitLengths[i] != lastBitLength) {
                    lastBitLength = sortedBitLengths[i]
                    codeIncrement = 1 shl 16 - lastBitLength
                }
                codes[permutation[i]] = code
            }

            // build the tree
            val tree = BinaryTree(maxLength)
            for (k in codes.indices) {
                val bitLength = originalBitLengths[k]
                if (bitLength > 0) {
                    tree.addLeaf(0, Integer.reverse(codes[k] shl 16), bitLength, k)
                }
            }
            return tree
        }
    }

    init {
        require(!(depth < 0 || depth > 30)) {
            ("depth must be bigger than 0 and not bigger than 30"
                    + " but is " + depth)
        }
        tree = IntArray(((1L shl depth + 1) - 1).toInt())
        Arrays.fill(tree, UNDEFINED)
    }
}