package com.bydmatecn.app.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Decode only a bounded-size thumbnail, not a full camera frame. */
internal fun decodeCapture(file: File): Bitmap {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720) sample *= 2
                return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("无法解码截图")

}
