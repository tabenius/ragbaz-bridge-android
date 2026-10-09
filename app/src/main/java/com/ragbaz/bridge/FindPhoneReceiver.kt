package com.ragbaz.bridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import org.json.JSONObject
import java.io.File
class FindPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != "com.ragbaz.bridge.FIND_PHONE") return
        val key = intent.getStringExtra("key") ?: ""
        val field = intent.getStringExtra("field") ?: "any"
        val useRegex = intent.getBooleanExtra("regex", false)
        val regexStr = intent.getStringExtra("regex_str") ?: ""
        val res = JSONObject()
        res.put("ok", false)
        res.put("key", key)
        try {
            val cr = context.contentResolver
            // simple search
            val selection = when (field) {
                "name" -> ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?"
                else -> null
            }
            val selArgs = if (field == "name" && !useRegex) arrayOf("%" + key + "%") else null
            val cur = cr.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                selection, selArgs, null)
            cur?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val pcur = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(id), null)
                    var bestNum: String? = null
                    pcur?.use { pc ->
                        val nidx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (pc.moveToNext()) {
                            bestNum = pc.getString(nidx) ?: bestNum
                        }
                    }
                    res.put("ok", true)
                    res.put("name", name)
                    res.put("number", bestNum ?: JSONObject.NULL)
                }
            }
        } catch (e: Exception) {
            res.put("reason", e.javaClass.simpleName)
        }
        try {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "bridge-last.json")
            file.writeText(res.toString(2))
        } catch (_: Exception) {}
    }
}
