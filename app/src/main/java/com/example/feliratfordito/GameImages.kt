package com.example.feliratfordito

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** A játékokhoz választott képek (a felhasználó galériájából), kicsinyítve a telefonon tárolva. */
object GameImages {
    private fun file(ctx: Context, id: Long) = File(ctx.filesDir, "jatekkep_$id.jpg")

    fun load(ctx: Context, id: Long): Bitmap? {
        val f = file(ctx, id)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /** A választott képet négyzetesre vágja, 384 px-re kicsinyíti és elmenti. */
    fun save(ctx: Context, id: Long, uri: Uri): Boolean = try {
        val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        }
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, 384, 384, true)
        file(ctx, id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (e: Exception) {
        false
    }

    fun delete(ctx: Context, id: Long) {
        file(ctx, id).delete()
    }
}
