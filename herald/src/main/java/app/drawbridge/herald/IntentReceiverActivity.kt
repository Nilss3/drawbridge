package app.drawbridge.herald

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point for links opened from other apps.
 *
 * Kept separate from [BrowserActivity] so herald can be registered as the
 * system's browser without external intents interfering with the task herald
 * already has open.
 */
class IntentReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forwarded = Intent(intent).apply {
            setClassName(applicationContext, BrowserActivity::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        startActivity(forwarded)
        finish()
    }
}
