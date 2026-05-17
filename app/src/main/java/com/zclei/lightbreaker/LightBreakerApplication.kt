package com.zclei.lightbreaker

import android.app.Application
import com.zclei.lightbreaker.data.LightBreakerDatabase
import com.zclei.lightbreaker.data.LightBreakerRepository
import com.zclei.lightbreaker.data.ProgressStore

class LightBreakerApplication : Application() {
    val repository: LightBreakerRepository by lazy {
        LightBreakerRepository(
            database = LightBreakerDatabase.get(this),
            progressStore = ProgressStore(this),
        )
    }
}
