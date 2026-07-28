package app.drawbridge.herald.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.applySystemBarInsets

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.root).applySystemBarInsets(top = true, bottom = true, sides = true)

        findViewById<Toolbar>(R.id.toolbar).apply {
            setTitle(R.string.menu_settings)
            setNavigationOnClickListener { finish() }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }
}
