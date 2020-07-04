package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.repositories.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class WalletController @Autowired constructor(
        private val walletRepository: WalletRepository
) {

    private val log = LoggerFactory.getLogger(WalletController::class.java)

    @MessageMapping("/createwalletentry")
    @SendTo("/topic/messages")
    @Transactional
    fun createWalletEntry(walletEntry: WalletEntry): SocketResponse<String> {
        return try {
            //TODO save other entry types other than "address"
            walletRepository.save(walletEntry)
            SocketResponse.Success(type = "createwalletentry", data = "${walletEntry.name} created!")
        } catch (e: Throwable) {
            val error = "Fatal error saving wallet entry!"
            log.error(error, e)
            SocketResponse.Error(type = "createwalletentry", exception = e)
        }
    }

    @MessageMapping("/deletewalletentry")
    @SendTo("/topic/messages")
    @Transactional
    fun deleteWalletEntry(id: Long): SocketResponse<String> {
        return try {
            val walletEntry = walletRepository.findByIdOrNull(id)
            if (walletEntry != null) {
                walletRepository.save(walletEntry.copy(name = walletEntry.name + "-${System.currentTimeMillis()}", deleted = true))
            }
            SocketResponse.Success(type = "deletewalletentry", data = "${walletEntry?.name} deleted!")
        } catch (e: Throwable) {
            val error = "Fatal error deleting wallet entry!"
            log.error(error, e)
            SocketResponse.Error(type = "deletewalletentry", exception = e)
        }
    }
}