package de.akkaoui.edelmetall

import android.app.Application
import de.akkaoui.edelmetall.work.PriceWorker

class EdelmetallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PriceWorker.schedule(this)
    }
}
