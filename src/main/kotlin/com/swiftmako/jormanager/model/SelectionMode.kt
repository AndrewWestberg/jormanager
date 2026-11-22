package com.swiftmako.jormanager.model

enum class SelectionMode {
    STANDARD,
    DEFRAGMENT,
    ;

    companion object {
        fun fromString(value: String?): SelectionMode =
            when (value?.trim()?.uppercase()) {
                "DEFRAGMENT", "UNFRACK", "CLEAN" -> DEFRAGMENT
                else -> STANDARD
            }
    }
}
