package com.teamshryne.anux

import android.app.Application
import com.teamshryne.anux.bootstrap.BootstrapManager
import com.teamshryne.anux.data.AnuxDatabase
import com.teamshryne.anux.data.AnuxPrefs
import com.teamshryne.anux.data.DistroRepository

class AnuxApp : Application() {
    val database: AnuxDatabase by lazy { AnuxDatabase.get(this) }
    val prefs: AnuxPrefs by lazy { AnuxPrefs(this) }
    val repository: DistroRepository by lazy { DistroRepository(this, database) }
    val bootstrap: BootstrapManager by lazy { BootstrapManager(this) }
}
