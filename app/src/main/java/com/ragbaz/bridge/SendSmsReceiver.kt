package com.ragbaz.bridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
class SendSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != "com.ragbaz.bridge.SEND_SMS") return
        val to = intent.getStringExtra("to") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val slot = intent.getIntExtra("slot", -1)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val now = Date()
        val result = JSONObject()
        result.put("ok", false)
        result.put("to", to)
        result.put("body_len", body.length)
        result.put("slot", slot)
        result.put("timestamp", sdf.format(now))
        result.put("reason", "unknown")
        try {
            if (to.isEmpty()) {
                result.put("reason", "missing_to")
                writeReceipt(context, result)
                return
            }
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subId = if (slot >= 0) {
                    val subs = subscriptionManager.activeSubscriptionInfoList
                    subs?.firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: SmsManager.getDefaultSmsSubscriptionId()
                } else {
                    SmsManager.getDefaultSmsSubscriptionId()
                }
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(to, null, body, null, null)
            result.put("ok", true)
            result.put("reason", "sent")
        } catch (e: Exception) {
            result.put("reason", e.javaClass.simpleName + ":" + (e.message ?: ""))
        }
        writeReceipt(context, result)
    }
    private fun writeReceipt(context: Context, json: JSONObject) {
        try {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "bridge-last.json")
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
        try {
            val tmp = File("/data/local/tmp/ragbaz-bridge-last.json")
            tmp.writeText(json.toString(2))
        } catch (_: Exception) {}
    }
}
