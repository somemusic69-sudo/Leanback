package com.leanback.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

sealed class ConnectionState {
    data class Disconnected(val reason: String? = null, val rejected: Boolean = false) : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class PendingApproval(val serverName: String, val code: String) : ConnectionState()
    data class Connected(
        val serverName: String,
        val host: String,
        val port: Int,
        val trusted: Boolean,
    ) : ConnectionState()
}

class LanClient(
    private val prefs: Prefs,
    private val onStateChanged: (ConnectionState) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentState: ConnectionState = ConnectionState.Disconnected()

    private var activeHost: String? = null
    private var activePort: Int = Prefs.DEFAULT_PORT
    private var manualDisconnect = false
    private var retryAttempts = 0
    private var connectionGen = 0

    private val retryRunnable = Runnable {
        val host = activeHost ?: return@Runnable
        if (!manualDisconnect) {
            openSocket(host, activePort)
        }
    }

    fun getState(): ConnectionState = currentState

    fun connect(host: String, port: Int, serverNameHint: String? = null, userInitiated: Boolean = true) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return

        mainHandler.removeCallbacks(retryRunnable)
        manualDisconnect = false
        if (userInitiated) {
            retryAttempts = 0
        }

        activeHost = cleanHost
        activePort = port
        prefs.lastHost = cleanHost
        prefs.lastPort = port
        if (!serverNameHint.isNullOrBlank()) {
            prefs.lastServerName = serverNameHint
        }

        openSocket(cleanHost, port)
    }

    fun reconnectIfSaved() {
        val savedHost = prefs.lastHost ?: return
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            return
        }
        val state = currentState
        if (state is ConnectionState.Disconnected && state.rejected) {
            return
        }
        connect(savedHost, prefs.lastPort, prefs.lastServerName, userInitiated = false)
    }

    fun disconnect(manual: Boolean = true) {
        manualDisconnect = manual
        mainHandler.removeCallbacks(retryRunnable)
        connectionGen++
        socket?.close(1000, if (manual) "User disconnected" else "App paused")
        socket = null
        updateState(ConnectionState.Disconnected(reason = null, rejected = false))
    }

    private fun openSocket(host: String, port: Int) {
        connectionGen++
        val gen = connectionGen
        socket?.cancel()
        socket = null

        updateState(ConnectionState.Connecting(host, port))

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != connectionGen) {
                        webSocket.close(1000, "Stale")
                        return
                    }
                    retryAttempts = 0
                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("deviceId", prefs.deviceId)
                        .put("deviceName", prefs.deviceName)
                    val token = prefs.getToken(host, port)
                    if (!token.isNullOrEmpty()) {
                        hello.put("token", token)
                    }
                    webSocket.send(hello.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != connectionGen) return
                    handleServerMessage(host, port, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen != connectionGen) return
                    handleSocketEnd(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != connectionGen) return
                    handleSocketEnd(-1, t.message ?: "Connection failed")
                }
            }
        )
    }

    private fun handleServerMessage(host: String, port: Int, raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }

        when (msg.optString("type")) {
            "pending" -> {
                val serverName = msg.optString("serverName", host)
                val code = msg.optString("code", "----")
                prefs.lastServerName = serverName
                updateState(ConnectionState.PendingApproval(serverName, code))
            }

            "welcome" -> {
                val serverName = msg.optString("serverName", host)
                val trusted = msg.optBoolean("trusted", false)
                val token = msg.optString("token", "")
                if (token.isNotEmpty()) {
                    prefs.saveToken(host, port, token)
                }
                prefs.lastServerName = serverName
                updateState(ConnectionState.Connected(serverName, host, port, trusted))
            }

            "rejected" -> {
                val reason = msg.optString("reason", "Connection denied on PC")
                manualDisconnect = true
                mainHandler.removeCallbacks(retryRunnable)
                updateState(ConnectionState.Disconnected(reason = reason, rejected = true))
            }

            "replaced" -> {
                val reason = msg.optString("reason", "Replaced by another phone")
                manualDisconnect = true
                mainHandler.removeCallbacks(retryRunnable)
                updateState(ConnectionState.Disconnected(reason = reason, rejected = false))
            }
        }
    }

    private fun handleSocketEnd(code: Int, reason: String) {
        socket = null
        val prev = currentState
        if (prev is ConnectionState.Disconnected && prev.rejected) {
            return
        }
        if (manualDisconnect || code == 4000 || code == 4003) {
            if (prev !is ConnectionState.Disconnected) {
                updateState(
                    ConnectionState.Disconnected(
                        reason = reason.takeIf { it.isNotBlank() },
                        rejected = code == 4003,
                    )
                )
            }
            return
        }

        updateState(ConnectionState.Disconnected(reason = reason.takeIf { it.isNotBlank() }))
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (manualDisconnect || activeHost == null) return
        val delayMs = when (retryAttempts) {
            0 -> 1200L
            1 -> 2500L
            else -> 4000L
        }
        retryAttempts++
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    private fun updateState(next: ConnectionState) {
        currentState = next
        mainHandler.post {
            onStateChanged(next)
        }
    }

    fun sendMove(dx: Float, dy: Float) {
        val ws = socket ?: return
        if (currentState !is ConnectionState.Connected) return
        // Drop stale move events if the Wi-Fi socket queue backs up.
        if (ws.queueSize() > 32 * 1024) return
        ws.send(
            JSONObject()
                .put("type", "move")
                .put("dx", dx.toDouble())
                .put("dy", dy.toDouble())
                .toString()
        )
    }

    fun sendClick(button: String) {
        sendConnected(JSONObject().put("type", "click").put("button", button))
    }

    fun sendMouseDown(button: String) {
        sendConnected(JSONObject().put("type", "mouseDown").put("button", button))
    }

    fun sendMouseUp(button: String) {
        sendConnected(JSONObject().put("type", "mouseUp").put("button", button))
    }

    fun sendScroll(dx: Float, dy: Float) {
        val ws = socket ?: return
        if (currentState !is ConnectionState.Connected) return
        if (ws.queueSize() > 32 * 1024) return
        ws.send(
            JSONObject()
                .put("type", "scroll")
                .put("dx", dx.toDouble())
                .put("dy", dy.toDouble())
                .toString()
        )
    }

    fun sendText(text: String, modifiers: List<String> = emptyList()) {
        if (text.isEmpty()) return
        val json = JSONObject().put("type", "text").put("text", text)
        if (modifiers.isNotEmpty()) {
            json.put("modifiers", JSONArray(modifiers))
        }
        sendConnected(json)
    }

    fun sendKey(key: String, modifiers: List<String> = emptyList(), repeat: Int = 1) {
        val json = JSONObject().put("type", "key").put("key", key)
        if (modifiers.isNotEmpty()) {
            json.put("modifiers", JSONArray(modifiers))
        }
        if (repeat > 1) {
            json.put("repeat", repeat)
        }
        sendConnected(json)
    }

    private fun sendConnected(payload: JSONObject) {
        val ws = socket ?: return
        if (currentState !is ConnectionState.Connected) return
        ws.send(payload.toString())
    }

    private class NoDelaySocketFactory : SocketFactory() {
        private val delegate = getDefault()

        private fun configure(s: Socket): Socket {
            try {
                s.tcpNoDelay = true
            } catch (_: Exception) {
            }
            return s
        }

        override fun createSocket(): Socket = configure(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket =
            configure(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            configure(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            configure(delegate.createSocket(address, port, localAddress, localPort))
    }
}
