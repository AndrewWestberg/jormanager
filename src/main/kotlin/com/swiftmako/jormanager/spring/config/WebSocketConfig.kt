package com.swiftmako.jormanager.spring.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {
    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/jormanager")
    }
    override fun configureWebSocketTransport(registration: WebSocketTransportRegistration) {
        // The configuration-restore endpoint bounds decoded ZIP input to 64 MiB.
        // Allow the corresponding base64 STOMP frame plus its JSON envelope.
        registration.setMessageSizeLimit(90 * 1024 * 1024)
    }


    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry
            .addEndpoint("/jormanager-websocket")
            .setAllowedOrigins(
                "http://localhost:8082",
                "chrome-extension://ggnhohnkfcpcanfekomdkjffnfcjnjam"
            )
//                .setAllowedOrigins("*")
            .withSockJS()
//                .setSupressCors(true)
    }
}
