package com.deepmost.rabbitav

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication
import timber.log.Timber

/** Swaps in Hilt's test application for instrumented tests. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        // HiltTestApplication skips RabbitAvApplication.onCreate, which is
        // where Timber is planted — without this, every pipeline log line
        // (RAV-*) is silently dropped in instrumented runs.
        if (Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
