package com.faceclaw.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Thin okhttp WebSocket wrapper for the TypeScript side. Sends text frames
 * (JSON protocols) and binary frames (raw audio for Soniox). Listener callbacks are
 * posted to the Looper of the thread that constructed this object, so a JS
 * isolate (main thread or app worker) always receives them on its own
 * thread; a Looper-less constructing thread falls back to the main thread.
 */
class FaceclawWebSocket
// Single constructor (no overloads) so NativeScript constructor resolution
// can never pick a variant that drops the auth header; callers with no
// header pass null/null.
constructor(url: String?, listener: FaceclawWebSocketListener?, headerName: String?, headerValue: String?) {
    private val callbackHandler: Handler
    private val callbackToken = Any()
    private val callbackLock = Any()
    private val socket: WebSocket
    private var queuedTextMessages = 0
    private var queuedTextBytes = 0L
    @Volatile
    private var closeRequested = false

    init {
        if (url == null || url.trim().isEmpty()) {
            throw IllegalArgumentException("url is required")
        }
        if (listener == null) {
            throw IllegalArgumentException("listener is required")
        }
        val looper = Looper.myLooper()
        callbackHandler = Handler(looper ?: Looper.getMainLooper())
        val builder = Request.Builder().url(url.trim())
        if (headerName != null && !headerName.isEmpty() && headerValue != null) {
            builder.addHeader(headerName, headerValue)
        }
        val request = builder.build()
        // Redacted diagnostics: confirm the auth header is actually present on
        // the handshake and carries a plausible value (not empty/truncated).
        val headerLog = StringBuilder()
        for (name in request.headers.names()) {
            val value = request.header(name)
            headerLog.append(name).append('=').append(redact(value)).append(' ')
        }
        Log.i(TAG, "ws connect host=" + request.url.host + request.url.encodedPath
            + " headers=[" + headerLog.toString().trim() + "]")
        socket = getClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                postControlCallback {
                    try {
                        listener.onOpen()
                    } catch (t: Throwable) {
                        Log.w(TAG, "listener onOpen failed", t)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val posted = postTextCallback(text) {
                    try {
                        listener.onTextMessage(text)
                    } catch (t: Throwable) {
                        Log.w(TAG, "listener onTextMessage failed", t)
                    }
                }
                if (!posted) abortForBacklog(listener)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                postControlCallback {
                    try {
                        listener.onClosed(code, reason)
                    } catch (t: Throwable) {
                        Log.w(TAG, "listener onClosed failed", t)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closeRequested) {
                    return
                }
                val message = t.toString()
                postControlCallback {
                    try {
                        listener.onFailure(message)
                    } catch (inner: Throwable) {
                        Log.w(TAG, "listener onFailure failed", inner)
                    }
                }
            }
        })
    }

    private fun postControlCallback(callback: () -> Unit) {
        callbackHandler.postAtTime({
            synchronized(callbackLock) {
                if (closeRequested) return@postAtTime
            }
            callback()
        }, callbackToken, SystemClock.uptimeMillis())
    }

    private fun postTextCallback(text: String, callback: () -> Unit): Boolean {
        val bytes = text.length.toLong() * 2L
        synchronized(callbackLock) {
            if (closeRequested || bytes > MAX_QUEUED_TEXT_BYTES || queuedTextMessages >= MAX_QUEUED_TEXT_MESSAGES ||
                queuedTextBytes + bytes > MAX_QUEUED_TEXT_BYTES) return false
            queuedTextMessages++
            queuedTextBytes += bytes
            val posted = callbackHandler.postAtTime({
                synchronized(callbackLock) {
                    if (queuedTextMessages > 0) queuedTextMessages--
                    queuedTextBytes = Math.max(0L, queuedTextBytes - bytes)
                    if (closeRequested) return@postAtTime
                }
                callback()
            }, callbackToken, SystemClock.uptimeMillis())
            if (!posted) {
                queuedTextMessages--
                queuedTextBytes -= bytes
            }
            return posted
        }
    }

    private fun abortForBacklog(listener: FaceclawWebSocketListener) {
        synchronized(callbackLock) {
            if (closeRequested) return
            closeRequested = true
            queuedTextMessages = 0
            queuedTextBytes = 0
        }
        callbackHandler.removeCallbacksAndMessages(callbackToken)
        try { socket.cancel() } catch (ignored: Throwable) { }
        callbackHandler.post {
            try { listener.onFailure("network:callback-backlog") }
            catch (t: Throwable) { Log.w(TAG, "listener onFailure failed", t) }
        }
    }

    private fun clearCallbacks() {
        synchronized(callbackLock) {
            queuedTextMessages = 0
            queuedTextBytes = 0
        }
        callbackHandler.removeCallbacksAndMessages(callbackToken)
    }

    companion object {
        private const val TAG = "FaceclawWebSocket"
        private const val MAX_QUEUED_TEXT_MESSAGES = 64
        private const val MAX_QUEUED_TEXT_BYTES = 8L * 1024L * 1024L
        @Volatile
        private var sharedClient: OkHttpClient? = null

        private fun getClient(): OkHttpClient {
            var client = sharedClient
            if (client == null) {
                synchronized(FaceclawWebSocket::class.java) {
                    if (sharedClient == null) {
                        sharedClient = OkHttpClient.Builder()
                            .pingInterval(20, TimeUnit.SECONDS)
                            .addInterceptor(FaceclawHttp.userAgentInterceptor())
                            .build()
                    }
                    client = sharedClient
                }
            }
            return client!!
        }

        private fun redact(value: String?): String {
            if (value == null) {
                return "null"
            }
            val len = value.length
            val prefix = value.substring(0, Math.min(4, len))
            return "len$len:$prefix..."
        }
    }

    fun sendText(message: String?): Boolean {
        return socket.send(message ?: "")
    }

    fun sendBinary(bytes: ByteArray?): Boolean {
        return socket.send((bytes ?: ByteArray(0)).toByteString())
    }

    fun close(code: Int, reason: String?) {
        closeRequested = true
        clearCallbacks()
        try {
            if (!socket.close(code, reason)) {
                socket.cancel()
            }
        } catch (t: Throwable) {
            socket.cancel()
        }
    }
}
