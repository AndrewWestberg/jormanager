package com.swiftmako.jormanager.utils

import java.util.*

class CircularQueue<T>(var maxElements:Int) : LinkedList<T>() {

    override fun add(element: T): Boolean {
        val result = super.add(element)
        while (this.size > maxElements) { super.remove(); }
        return result
    }

}