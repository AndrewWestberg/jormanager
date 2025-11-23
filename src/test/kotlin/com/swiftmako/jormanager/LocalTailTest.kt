package com.swiftmako.jormanager

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class LocalTailTest {
    @Test
    fun testTail() {
        val process = ProcessBuilder("/bin/bash", "-c", "cat /home/westbam/haskell/prfit/logs/node-*.json | grep --line-buffered \"TraceAdoptedBlock\"").start()
        process.inputStream.bufferedReader().use { reader ->
            reader.lines().forEach { line ->
                println(line)
            }
        }
        process.errorStream.bufferedReader().use { reader ->
            reader.lines().forEach { line ->
                println(line)
            }
        }
        process.waitFor(60, TimeUnit.SECONDS)
    }
}
