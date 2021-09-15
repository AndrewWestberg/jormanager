package com.swiftmako.jormanager.monitors.utils

import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.repositories.ChainRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

object ChainRepositoryHelper {

    fun getChainBlocksForSyncStart(chainRepository: ChainRepository): List<ChainBlock> {
        // Clean up old versions less than 20 so they re-sync
        chainRepository.deleteEmptyEta()

        val page = chainRepository.findAll(PageRequest.of(0, 64, Sort.Direction.DESC, "slotNumber"))

        return page.content.filterIndexed { index, _ ->
            // all powers of 2 including 0th element 0, 2, 4, 8, 16, 32, 64
            index == 0 || (index > 1 && (index and (index - 1) == 0))
        }
    }

}