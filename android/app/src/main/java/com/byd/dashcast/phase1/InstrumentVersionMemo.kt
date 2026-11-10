package com.byd.dashcast.phase1

import android.content.Context
import android.content.SharedPreferences

/** The version the instrument itself last reported, kept across restarts of the app. */
internal object InstrumentVersionMemo {
    private const val FILE = "dashcast.product.v1"
    private const val VERSION_ENTRY = "instrument_version"

    @Volatile
    private var store: SharedPreferences? = null

    @Volatile
    private var latest: String? = null

    @JvmStatic
    fun attach(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        store = preferences
        latest = preferences.getString(VERSION_ENTRY, null)
    }

    @JvmStatic
    fun report(version: String) {
        latest = version
        store?.edit()?.putString(VERSION_ENTRY, version)?.apply()
    }

    /** Text for the screen: the instrument only answers over a projection connection. */
    @JvmStatic
    fun shown(): String = latest?.let { "$it（最近一次投屏时读取）" } ?: "投屏一次后显示"
}
