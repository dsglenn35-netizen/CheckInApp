package com.example.checkin.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** 按目标尺寸解码本地图片（采样压缩，防止大图 OOM），失败返回 null */
fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
