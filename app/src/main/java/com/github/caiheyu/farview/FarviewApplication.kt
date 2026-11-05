package com.github.caiheyu.farview

import android.app.Application
import com.github.caiheyu.farview.session.SessionManager
import com.github.caiheyu.farview.update.UpdateManager

class FarviewApplication : Application() {
    val session by lazy { SessionManager(this) }
    val updates by lazy { UpdateManager(this) }
}
