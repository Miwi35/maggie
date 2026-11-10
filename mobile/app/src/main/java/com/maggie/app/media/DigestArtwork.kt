package com.maggie.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/** The violet thumbnail with a rising sun of the mockup, drawn once; null if the device cannot draw it. */
object DigestArtwork {
    private const val SIZE = 512

    val png: ByteArray? by lazy {
        runCatching {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), 0xFF3B1F7A.toInt(), 0xFFB36BD6.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
            paint.shader = null
            paint.color = 0xFFFFD27A.toInt()
            canvas.drawCircle(SIZE / 2f, SIZE * 0.72f, SIZE * 0.2f, paint)
            paint.color = 0xFF3B1F7A.toInt()
            canvas.drawRect(0f, SIZE * 0.78f, SIZE.toFloat(), SIZE.toFloat(), paint)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }
}
