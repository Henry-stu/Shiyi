package com.hh.recall

import android.util.Log

/** Small debug-only event logger. Never include article text or draft contents. */
internal object AppLog {
    private const val TAG = "Recall"

    fun event(name: String, details: String = "") {
        if (BuildConfig.DEBUG) Log.d(TAG, if (details.isEmpty()) name else "$name $details")
    }

    fun failure(name: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.e(TAG, name, error)
    }
}
