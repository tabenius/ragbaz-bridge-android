package com.ragbaz.bridge
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
class ContactsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != "com.ragbaz.bridge.CONTACTS") return
        val query = intent.getStringExtra("query") ?: ""
        val field = intent.getStringExtra("field") ?: "any" // any, name, number, email
        val regex = intent.getStringExtra("regex") ?: ""
        val limit = intent.getIntExtra("limit", 50)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val result = JSONObject()
        result.put("ok", true)
        result.put("query", query)
        result.put("field", field)
        result.put("regex", regex)
        result.put("timestamp", sdf.format(Date()))
        try {
            val contacts = JSONArray()
            val cr = context.contentResolver
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )
            val cursor: Cursor? = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC LIMIT " + Math.max(1, limit)
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                val pattern = if (regex.isNotEmpty()) Pattern.compile(regex, Pattern.CASE_INSENSITIVE) else null
                while (c.moveToNext()) {
                    val id = c.getString(idIdx)
                    val name = c.getString(nameIdx) ?: ""
                    val phones = JSONArray()
                    val pcur = cr.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id),
                        null
                    )
                    pcur?.use { pc ->
                        val numIdx = pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (pc.moveToNext()) {
                            phones.put(pc.getString(numIdx) ?: "")
                        }
                    }
                    val emails = JSONArray()
                    val ecur = cr.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                        arrayOf(id),
                        null
                    )
                    ecur?.use { ec ->
                        val addrIdx = ec.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        while (ec.moveToNext()) {
                            emails.put(ec.getString(addrIdx) ?: "")
                        }
                    }
                    var match = query.isEmpty()
                    if (!match && pattern != null) {
                        when (field) {
                            "name" -> match = pattern.matcher(name).find()
                            "number" -> {
                                var m = false
                                for (i in 0 until phones.length()) {
                                    if (pattern.matcher(phones.getString(i)).find()) { m = true; break }
                                }
                                match = m
                            }
                            "email" -> {
                                var m = false
                                for (i in 0 until emails.length()) { if (pattern.matcher(emails.getString(i)).find()) { m = true; break } }
                                match = m
                            }
                            else -> {
                                match = pattern.matcher(name).find()
                                if (!match) {
                                    for (i in 0 until phones.length()) { if (pattern.matcher(phones.getString(i)).find()) { match = true; break } }
                                }
                                if (!match) {
                                    for (i in 0 until emails.length()) { if (pattern.matcher(emails.getString(i)).find()) { match = true; break } }
                                }
                            }
                        }
                    } else if (!match) {
                        when (field) {
                            "name" -> match = name.contains(query, ignoreCase = true)
                            "number" -> {
                                var m = false
                                for (i in 0 until phones.length()) { if (phones.getString(i).contains(query)) { m = true; break } }
                                match = m
                            }
                            "email" -> {
                                var m = false
                                for (i in 0 until emails.length()) { if (emails.getString(i).contains(query, ignoreCase = true)) { m = true; break } }
                                match = m
                            }
                            else -> {
                                match = name.contains(query, ignoreCase = true)
                                if (!match) {
                                    for (i in 0 until phones.length()) { if (phones.getString(i).contains(query)) { match = true; break } }
                                }
                                if (!match) {
                                    for (i in 0 until emails.length()) { if (emails.getString(i).contains(query, ignoreCase = true)) { match = true; break } }
                                }
                            }
                        }
                    }
                    if (match) {
                        val obj = JSONObject()
                        obj.put("id", id)
                        obj.put("name", name)
                        obj.put("numbers", phones)
                        obj.put("emails", emails)
                        contacts.put(obj)
                    }
                }
            }
            result.put("count", contacts.length())
            result.put("contacts", contacts)
        } catch (e: Exception) {
            result.put("ok", false)
            result.put("reason", e.javaClass.simpleName + ":" + (e.message ?: ""))
        }
        writeReceipt(context, result)
    }
    private fun writeReceipt(context: Context, json: JSONObject) {
        // /data/local/tmp is not writable from an app sandbox (SELinux), so
        // the receipt goes to the app-specific external dir, which adb shell
        // can read without run-as or extra permissions.
        try {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "bridge-last.json")
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
    }
}
