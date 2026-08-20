package particlesim.core

/**
 * A resizable primitive `double` array — like `ArrayList` but without boxing, so
 * struct-of-arrays particle fields (§9.3) stay cache-friendly as particle count grows.
 */
class GrowableDoubleArray(initialCapacity: Int = 16) {
    private var array = DoubleArray(initialCapacity)

    var size: Int = 0
        private set

    operator fun get(index: Int): Double {
        require(index in 0 until size) { "index $index out of bounds for size $size" }
        return array[index]
    }

    operator fun set(index: Int, value: Double) {
        require(index in 0 until size) { "index $index out of bounds for size $size" }
        array[index] = value
    }

    /** Appends [value], growing the backing array if needed, and returns its index. */
    fun add(value: Double): Int {
        ensureCapacity(size + 1)
        array[size] = value
        return size++
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > array.size) {
            array = array.copyOf(maxOf(minCapacity, array.size * 2))
        }
    }
}
