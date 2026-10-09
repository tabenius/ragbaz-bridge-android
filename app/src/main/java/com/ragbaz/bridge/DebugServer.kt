package com.ragbaz.bridge

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Debug-only, activity-scoped loopback link. Use adb forward, never mesh relays. */
class DebugServer(private val port: Int = 8769) : AutoCloseable {
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
                    connection.start()
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
        private var sequence = 0L

        @Synchronized fun send(kind: String, payload: JSONObject, replyTo: Long? = null) {
            if (!active) return
            val envelope = JSONObject()
                .put("schema", "ragbaz.debug/1").put("source", "ragbaz-bridge-android")
                .put("seq", ++sequence).put("kind", kind)
                .put("utc_ns", System.currentTimeMillis() * 1_000_000L)
                .put("monotonic_ns", SystemClock.elapsedRealtimeNanos())
                .put("payload", payload)
            if (replyTo != null) envelope.put("reply_to", replyTo)
            val frame = (envelope.toString() + "\n").toByteArray(Charsets.UTF_8)
            // A stalled peer is disconnected, never allowed to grow memory unbounded.
            if (frame.size > 65536 || !output.offer(frame)) close()
        }

        fun start() {
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
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read()
                            if (byte < 0) throw java.io.EOFException()
                            if (bytes.size() >= 65535) throw IllegalArgumentException("oversized frame")
                            if (byte == 10) break
                            bytes.write(byte)
                        }
                        val message = JSONObject(bytes.toString("UTF-8"))
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
