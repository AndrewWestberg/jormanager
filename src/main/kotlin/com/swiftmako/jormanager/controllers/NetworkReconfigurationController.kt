package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.NetworkReconfigurationReview
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.services.ReconfigurationAuthorizationRequest
import com.swiftmako.jormanager.services.ReconfigurationPreviewRequest
import com.swiftmako.jormanager.services.ReviewedNetworkReconfiguration
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class NetworkReconfigurationController(private val reviews: ReviewedNetworkReconfiguration) {
    @MessageMapping("/network_reconfiguration_preview")
    @SendTo("/topic/messages")
    fun preview(request: ReconfigurationPreviewRequest): SocketResponse<NetworkReconfigurationReview> = try {
        SocketResponse.Success("network_reconfiguration_preview", reviews.preview(request))
    } catch (e: Exception) {
        SocketResponse.Error("network_reconfiguration_preview", e)
    }

    @MessageMapping("/network_reconfiguration_authorize")
    @SendTo("/topic/messages")
    fun authorize(request: ReconfigurationAuthorizationRequest): SocketResponse<NetworkReconfigurationReview> = try {
        SocketResponse.Success("network_reconfiguration_authorize", reviews.authorize(request))
    } catch (e: Exception) {
        SocketResponse.Error("network_reconfiguration_authorize", e)
    }
}
