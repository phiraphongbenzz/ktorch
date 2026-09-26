package com.phiraphongbenzz.ktorch.sample

import android.app.Application

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalServer.start()
    }
}
