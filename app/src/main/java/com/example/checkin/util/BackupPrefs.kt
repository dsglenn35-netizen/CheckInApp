package com.example.checkin.util

import android.content.Context

/**
 * 「备份包含照片」开关的本地持久化。
 *
 * 照片以 base64 内嵌进备份 JSON，体积会显著增大（每张 100KB~2MB），
 * 因此默认关闭，由用户按需开启。开启后换机恢复也能带回取证照片。
 */
object BackupPrefs {
    private const val NAME = "backup_prefs"
    private const val KEY_WITH_PHOTOS = "with_photos"

    fun withPhotos(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WITH_PHOTOS, false)

    fun setWithPhotos(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WITH_PHOTOS, enabled)
            .apply()
    }
}
