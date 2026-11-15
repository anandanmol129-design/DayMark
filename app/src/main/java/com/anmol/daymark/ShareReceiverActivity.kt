package com.anmol.daymark

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Target of the system share sheet ("Add to saves"). Saves what was shared and closes right away. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveAndFinish(intent)
    }

    // A second share can arrive while this instance is still closing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveAndFinish(intent)
    }

    private fun saveAndFinish(intent: Intent) {
        val saved = runCatching { Saves.saveShared(this, intent) }.getOrDefault(0)
        Toast.makeText(this, if (saved > 0) "Added to saves" else "Nothing to save", Toast.LENGTH_SHORT).show()
        finish()
    }
}
