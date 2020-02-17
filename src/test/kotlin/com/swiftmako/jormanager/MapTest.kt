package com.swiftmako.jormanager

import org.junit.jupiter.api.Test

class MapTest {

    @Test
    fun testMapIterator() {
        val map = mutableMapOf<Int, Boolean>()
        for (i in 0..4) {
            map[i] = false
        }

        println("Before: $map")

        map.iterator().forEach { mutableEntry ->
            mutableEntry.setValue(true)
        }

        println("After: $map")
    }
}