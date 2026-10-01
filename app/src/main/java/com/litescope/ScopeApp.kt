package com.litescope

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.litescope.core.Prefs
import com.litescope.service.NotificationFactory
import com.litescope.view.ScopeTheme

class ScopeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Create the notification channel as early as possible so the capture notification can be
        // posted the moment the foreground service starts.
        NotificationFactory.createChannel(this)

        // Light scope backgrounds get a light app theme so the Material widgets match.
        val prefs = Prefs.get(this)
        AppCompatDelegate.setDefaultNightMode(
            if (ScopeTheme.isLightBackground(prefs.scopeBackground)) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}
