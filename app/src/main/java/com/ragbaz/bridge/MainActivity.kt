package com.ragbaz.bridge
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
class MainActivity : AppCompatActivity() {
    private var debugServer: DebugServer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            try {
                debugServer = DebugServer().also { it.start() }
            } catch (error: java.io.IOException) {
                android.util.Log.e("RagbazBridge", "Debug listener unavailable", error)
            }
        }
    }

    override fun onDestroy() {
        debugServer?.close()
        super.onDestroy()
    }
}
