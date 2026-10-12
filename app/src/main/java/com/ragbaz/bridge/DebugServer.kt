package com.ragbaz.bridge

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Debug-only, activity-scoped loopback link. Use adb forward, never mesh relays. */
class DebugServer(private val keyFile: File, private val port: Int = 8769) : AutoCloseable {
    @Volatile private var running = false
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Connection>()

    @Synchronized fun start() {
        if (running) return
        val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        listener = server
        running = true
        acceptThread = thread(name = "ragbaz-debug-accept", isDaemon = true) {
            try {
                while (running) {
                    val connection = Connection(server.accept())
                    connections.add(connection)
                    thread(name = "ragbaz-debug-auth", isDaemon = true) { connection.start() }
                }
            } catch (_: java.io.IOException) {
                // Closing the listener interrupts accept during activity shutdown.
            }
        }
    }

    /** Send an unsolicited event to connected Indra clients without blocking. */
    fun publish(kind: String, payload: JSONObject) {
        connections.forEach { it.send(kind, payload) }
    }

    @Synchronized override fun close() {
        running = false
        listener?.close()
        acceptThread?.join(1000)
        connections.toList().forEach { it.close() }
    }

    private inner class Connection(private val socket: Socket) : AutoCloseable {
        private val output = ArrayBlockingQueue<ByteArray>(128)
        private val timer = Executors.newSingleThreadScheduledExecutor()
        @Volatile private var active = true
        @Volatile private var authenticated = false
        private var sequence = 0L
        private lateinit var sendKey: ByteArray
        private lateinit var receiveKey: ByteArray

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun unhex(text: String): ByteArray {
            require(text.length == 64 && text.all { it in "0123456789abcdefABCDEF" })
            return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        private fun mac(key: ByteArray, bytes: ByteArray): ByteArray {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(key, "HmacSHA256"))
            return hmac.doFinal(bytes)
        }
        private fun proof(key: ByteArray, role: String, server: ByteArray = byteArrayOf(),
                          client: ByteArray = byteArrayOf()) =
            mac(key, ("ragbaz.debug-auth/1\u0000" + role + "\u0000").toByteArray(Charsets.UTF_8) + server + client)

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) throw java.io.EOFException()
                if (bytes.size() >= 65535) throw IllegalArgumentException("oversized frame")
                if (byte == 10) return bytes.toString("UTF-8")
                bytes.write(byte)
            }
        }

        private fun authenticate() {
            val key = unhex(keyFile.readText().trim())
            val server = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val stream = socket.getOutputStream()
            val challenge = JSONObject().put("schema", "ragbaz.debug-auth/1")
                .put("kind", "challenge").put("nonce", hex(server))
            stream.write((challenge.toString() + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            socket.soTimeout = 5000
            val response = JSONObject(line(socket.getInputStream()))
            require(response.getString("schema") == "ragbaz.debug-auth/1" && response.getString("kind") == "proof")
            val client = unhex(response.getString("nonce"))
            require(MessageDigest.isEqual(unhex(response.getString("proof")), proof(key, "host", server, client)))
            val accepted = JSONObject().put("schema", "ragbaz.debug-auth/1")
                .put("kind", "accepted").put("proof", hex(proof(key, "bridge", server, client)))
            stream.write((accepted.toString() + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            val session = proof(key, "session", server, client)
            receiveKey = proof(session, "host-to-bridge")
            sendKey = proof(session, "bridge-to-host")
            socket.soTimeout = 0
            authenticated = true
        }

        @Synchronized fun send(kind: String, payload: JSONObject, replyTo: Long? = null) {
            if (!active || !authenticated) return
            val envelope = JSONObject()
                .put("schema", "ragbaz.debug/1").put("source", "ragbaz-bridge-android")
                .put("seq", ++sequence).put("kind", kind)
                .put("utc_ns", System.currentTimeMillis() * 1_000_000L)
                .put("monotonic_ns", SystemClock.elapsedRealtimeNanos())
                .put("payload", payload)
            if (replyTo != null) envelope.put("reply_to", replyTo)
            val body = envelope.toString()
            val signed = JSONObject().put("body", body).put("mac", hex(mac(sendKey, body.toByteArray(Charsets.UTF_8))))
            val frame = (signed.toString() + "\n").toByteArray(Charsets.UTF_8)
            // A stalled peer is disconnected, never allowed to grow memory unbounded.
            if (frame.size > 65536 || !output.offer(frame)) close()
        }

        fun start() {
            try {
                authenticate()
            } catch (_: Exception) {
                close()
                return
            }
            thread(name = "ragbaz-debug-write", isDaemon = true) {
                try {
                    val stream = socket.getOutputStream()
                    while (active) {
                        val frame = output.poll(1, TimeUnit.SECONDS) ?: continue
                        stream.write(frame)
                        stream.flush()
                    }
                } catch (_: Exception) {
                    close()
                }
            }
            send("hello", JSONObject().put("protocol", "ragbaz.debug/1"))
            timer.scheduleAtFixedRate({ send("heartbeat", JSONObject()) }, 1, 1, TimeUnit.SECONDS)
            thread(name = "ragbaz-debug-read", isDaemon = true) {
                try {
                    val input = socket.getInputStream()
                    var previousSeq = 0L
                    while (active) {
                        val signed = JSONObject(line(input))
                        val body = signed.getString("body")
                        require(MessageDigest.isEqual(unhex(signed.getString("mac")), mac(receiveKey, body.toByteArray(Charsets.UTF_8))))
                        val message = JSONObject(body)
                        require(message.getString("schema") == "ragbaz.debug/1")
                        require(message.getString("source").isNotEmpty())
                        require(message.getString("kind").isNotEmpty())
                        val seq = message.getLong("seq")
                        require(seq > previousSeq)
                        previousSeq = seq
                        require(message.getLong("utc_ns") >= 0 && message.getLong("monotonic_ns") >= 0)
                        val receivedUtc = System.currentTimeMillis() * 1_000_000L
                        val receivedMono = SystemClock.elapsedRealtimeNanos()
                        send("receipt", JSONObject().put("kind", message.getString("kind"))
                            .put("payload", message.getJSONObject("payload"))
                            .put("received_utc_ns", receivedUtc)
                            .put("received_monotonic_ns", receivedMono), seq)
                    }
                } catch (_: Exception) {
                    close()
                }
            }
        }

        @Synchronized override fun close() {
            if (!active) return
            active = false
            timer.shutdownNow()
            socket.close()
            connections.remove(this)
        }
    }
}
