package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.repositories.BlockRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class BlockController @Autowired constructor(private val blockRepository: BlockRepository) {

    @GetMapping("/api/makeblocks")
    fun makeBlocks(): List<Block> {
        blockRepository.save(
                Block(
                        pool = "bcsh", host = "papa", slot = 123, hash = "lskjdflskjdflksjdflskdjf"
                )
        )

        return blockRepository.findAll()
    }

}