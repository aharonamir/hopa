package com.hopa.backend.pairing

import java.net.URI

/**
 * The HTTP side of the gateway's origin: pairing and token refresh are plain
 * HTTPS on the same host and port the WebSocket URL names.
 */
object BackendUrls {
    fun httpBase(gatewayUrl: String): String {
        val trimmed = gatewayUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.trimEnd('/')
        val scheme = when (uri.scheme?.lowercase()) {
            "wss", "https" -> "https"
            "ws", "http" -> "http"
            else -> return trimmed.trimEnd('/')
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return trimmed.trimEnd('/')
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /**
     * The socket URL a client actually opens: `/v1/gateway` on the same host and
     * port, with the scheme the gateway expects. Sign-up stores an HTTP origin,
     * so this is the one place that turns it into a WebSocket URL; an input that
     * already names the gateway stays unchanged.
     */
    fun gatewayUrl(base: String): String {
        val http = httpBase(base)
        val socket = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> return http.trimEnd('/') + "/v1/gateway"
        }
        return socket.trimEnd('/') + "/v1/gateway"
    }
}
