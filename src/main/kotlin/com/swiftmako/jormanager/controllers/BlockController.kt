package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.repositories.BlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class BlockController @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val moshi: Moshi
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    @GetMapping("/api/makeblocks")
    fun makeBlocks(): List<Block> {
        blockRepository.save(
                Block(
                        pool = "bcsh", host = "papa", slot = 123, hash = "lskjdflskjdflksjdflskdjf"
                )
        )

        return blockRepository.findAll()
    }

    @GetMapping("/api/logs")
    fun printLogs(): String {
        GlobalScope.launch(Dispatchers.IO) {
            val ssh = SSHClient()
            ssh.loadKnownHosts()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect("papa", 15795)
            try {
                val base = "${System.getProperty("user.home")}${File.separator}.ssh${File.separator}"
                ssh.authPublickey("westbam", "$base/tux_private.pem")
                ssh.startSession().use { session ->
                    val cmd = session.exec("tail -f --retry -n +0 /home/westbam/haskell/bcsh/logs/node.json | grep --line-buffered \"TraceAdoptedBlock\"")
                    cmd.inputStream.bufferedReader().use {
                        val adapter = moshi.adapter(TraceAdoptedBlock::class.java)
                        it.forEachLine { line ->
                            adapter.fromJson(line)?.let { traceAdoptedBlock ->
                                log.error("$traceAdoptedBlock")
                                blockRepository.save(
                                        Block(
                                                pool = "bcsh",
                                                host = traceAdoptedBlock.host,
                                                slot = traceAdoptedBlock.block.slot,
                                                hash = traceAdoptedBlock.block.rawHash()
                                        )
                                )
                            }
                        }
                    }
                }
            } finally {
                ssh.disconnect()
            }
        }
        return "OK"
    }

}