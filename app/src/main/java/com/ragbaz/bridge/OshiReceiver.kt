package com.ragbaz.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import oshi.SystemInfo
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** BroadcastReceiver that collects system metrics via the OSHI library and writes a receipt. */
class OshiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Action "com.ragbaz.bridge.OSHI" triggers the metric collection
        if (action != "com.ragbaz.bridge.OSHI") return

        val result = JSONObject()
        result.put("ok", true)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        timestamp.timeZone = TimeZone.getTimeZone("UTC")
        result.put("timestamp", timestamp.format(Date()))

        try {
            val systemInfo = SystemInfo()
            val os = systemInfo.operatingSystem
            val processor = systemInfo.hardware.processor
            val memory = systemInfo.hardware.memory

            // OS information
            result.put("os_name", os.family)
            result.put("os_version", os.versionInfo.version)
            result.put("os_arch", System.getProperty("os.arch") ?: "unknown")

            // CPU information
            // Load average is not utilization. A percentage needs a second tick
            // sample; don't block a BroadcastReceiver or fabricate that sample.
            result.put("cpu_load_percent", JSONObject.NULL)
            val load = JSONArray()
            processor.getSystemLoadAverage(3).forEach { value ->
                load.put(if (value >= 0 && value.isFinite()) value else JSONObject.NULL)
            }
            result.put("cpu_load_average", load)
            val frequencies = JSONArray()
            processor.currentFreq.forEach { value ->
                frequencies.put(if (value >= 0) value / 1_000_000 else JSONObject.NULL)
            }
            result.put("cpu_current_mhz", frequencies)

            // Memory information (total and available in bytes)
            val totalMem = memory.getTotal()
            val availableMem = memory.getAvailable()
            result.put("memory_total_bytes", if (totalMem > 0) totalMem else JSONObject.NULL)
            result.put("memory_available_bytes", if (totalMem > 0) availableMem else JSONObject.NULL)

            // Battery is handled separately; include a placeholder if present
            result.put("battery_level", JSONObject.NULL)
        } catch (e: Exception) {
            result.put("ok", false)
            result.put("reason", e.javaClass.simpleName + ":" + (e.message ?: ""))
            Log.e("OshiReceiver", "OSHI metric collection failed", e)
        } catch (e: LinkageError) {
            // Optional native metrics must not take down the rest of the bridge.
            result.put("ok", false)
            result.put("reason", "oshi_native_unavailable: ${e.javaClass.simpleName}")
            Log.e("OshiReceiver", "OSHI native backend unavailable", e)
        }

        // Both successful results and errors must reach the accessible receipt.
        try {
            val file = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "bridge-last.json")
            file.writeText(result.toString(2))
        } catch (e: Exception) {
            Log.e("OshiReceiver", "Failed to write bridge receipt", e)
        }

        // Also try writing to /data/local/tmp as a legacy fallback
        try {
            val tmp = java.io.File("/data/local/tmp/ragbaz-bridge-last.json")
            tmp.writeText(result.toString(2))
        } catch (_: Exception) {
            // Ignore - /data/local/tmp may not be writable from app sandbox
        }
    }
}
