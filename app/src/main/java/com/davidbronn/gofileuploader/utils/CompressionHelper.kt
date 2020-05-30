@file:Suppress("DEPRECATION")

package com.davidbronn.gofileuploader.utils

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

class CompressionHelper private constructor(private val context: Context) {

    private var quality = 80
    private var maxWidth = 612.0f
    private var maxHeight = 816.0f
    private var fileName: String? = null
    private var fileNamePrefix: String? = null
    private var destinationFilePath: String = ""
    private var bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
    private var compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

    fun getAsFile(scope: CoroutineScope, uri: Uri, body: (File) -> Unit) {
        if (destinationFilePath.isBlank()) {
            throw RuntimeException("Destination File Path cannot be blank!")
        }
        scope.launch {
            val compressedFile = compressImage(
                uri, maxWidth, maxHeight,
                compressFormat, quality, destinationFilePath
            )
            body(compressedFile)
        }
    }

    fun getAsBitmap(scope: CoroutineScope, uri: Uri, body: (Bitmap?) -> Unit) {
        if (destinationFilePath.isBlank()) {
            throw RuntimeException("Destination File Path cannot be blank!")
        }
        val bmp = context.sampledBitmap(uri, maxWidth, maxHeight)
        body(bmp)
    }

    class Builder private constructor() {
        private lateinit var compressor: CompressionHelper

        constructor(context: Context, init: Builder.() -> Unit) : this() {
            compressor = CompressionHelper(context)
            init()
        }

        fun maxWidth(init: Builder.() -> Float) = apply { compressor.maxWidth = init() }

        fun maxHeight(init: Builder.() -> Float) = apply { compressor.maxHeight = init() }

        fun compressFormat(init: Builder.() -> Bitmap.CompressFormat) =
            apply { compressor.compressFormat = init() }

        fun bitmapConfig(init: Builder.() -> Bitmap.Config) =
            apply { compressor.bitmapConfig = init() }

        fun quality(init: Builder.() -> Int) = apply { compressor.quality = init() }

        fun destinationDirectoryPath(init: Builder.() -> String) =
            apply { compressor.destinationFilePath = init() }

        fun fileNamePrefix(init: Builder.() -> String) =
            apply { compressor.fileNamePrefix = init() }

        fun fileName(init: Builder.() -> String) = apply { compressor.fileName = init() }

        fun build(): CompressionHelper {
            return compressor
        }
    }

    @Throws(IOException::class)
    private suspend fun compressImage(
        uri: Uri,
        reqWidth: Float,
        reqHeight: Float,
        compressFormat: Bitmap.CompressFormat?,
        quality: Int,
        destinationPath: String
    ): File {
        return withContext(Dispatchers.IO) {
            var fileOutputStream: FileOutputStream?
            val file = File(destinationPath).parentFile

            file?.run {
                if (!exists()) mkdirs()
                fileOutputStream = FileOutputStream(destinationPath)

                try {
                    val decodedSampledBitmap =
                        context.sampledBitmap(uri, reqWidth, reqHeight)
                    decodedSampledBitmap?.run {
                        compress(compressFormat, quality, fileOutputStream)
                    }
                } finally {
                    if (fileOutputStream != null) {
                        fileOutputStream?.flush()
                        fileOutputStream?.close()
                    }
                }
            }
            File(destinationPath)
        }
    }

    @Throws(IOException::class)
    private fun Context.sampledBitmap(
        uri: Uri,
        reqWidth: Float,
        reqHeight: Float
    ): Bitmap? {
        var bmp: Bitmap?
        var scaledBitmap: Bitmap? = null
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        val fileDescriptor = getFileDescriptor(uri)
        bmp = getBitmapFromUri(uri, options)
        var actualHeight = options.outHeight
        var actualWidth = options.outWidth
        var imgRatio = actualWidth.toFloat() / actualHeight.toFloat()
        val maxRatio = reqWidth / reqHeight
        if (actualHeight > reqHeight || actualWidth > reqWidth) {
            when {
                imgRatio < maxRatio -> {
                    imgRatio = reqHeight / actualHeight
                    actualWidth = (imgRatio * actualWidth).toInt()
                    actualHeight = reqHeight.toInt()
                }
                imgRatio > maxRatio -> {
                    imgRatio = reqWidth / actualWidth
                    actualHeight = (imgRatio * actualHeight).toInt()
                    actualWidth = reqWidth.toInt()
                }
                else -> {
                    actualHeight = reqHeight.toInt()
                    actualWidth = reqWidth.toInt()
                }
            }
        }

        options.inSampleSize = calculateInSampleSize(
            options,
            actualWidth,
            actualHeight
        )
        options.inJustDecodeBounds = false
        options.inDither = false
        options.inPurgeable = true
        options.inInputShareable = true
        options.inTempStorage = ByteArray(16 * 1024)
        try {
            bmp = getBitmapFromUri(uri, options)
        } catch (exception: OutOfMemoryError) {
            exception.printStackTrace()
        }
        try {
            scaledBitmap = Bitmap.createBitmap(actualWidth, actualHeight, Bitmap.Config.ARGB_8888)
        } catch (exception: OutOfMemoryError) {
            exception.printStackTrace()
        }
        val ratioX = actualWidth / options.outWidth.toFloat()
        val ratioY = actualHeight / options.outHeight.toFloat()
        val middleX = actualWidth / 2.0f
        val middleY = actualHeight / 2.0f
        val scaleMatrix = Matrix()
        scaleMatrix.setScale(ratioX, ratioY, middleX, middleY)
        val canvas = Canvas(scaledBitmap!!)
        canvas.setMatrix(scaleMatrix)
        canvas.drawBitmap(
            bmp!!,
            middleX - bmp.width / 2,
            middleY - bmp.height / 2,
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        bmp.recycle()
        val exif: ExifInterface
        try {
            exif = ExifInterface(fileDescriptor)
            val orientation =
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            val matrix = Matrix()
            when (orientation) {
                6 -> {
                    matrix.postRotate(90f)
                }
                3 -> {
                    matrix.postRotate(180f)
                }
                8 -> {
                    matrix.postRotate(270f)
                }
            }
            scaledBitmap = Bitmap.createBitmap(
                scaledBitmap, 0, 0, scaledBitmap.width,
                scaledBitmap.height, matrix, true
            )
        } catch (e: IOException) {
            Log.e("Log", "", e)
        }
        return scaledBitmap
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            inSampleSize *= 2
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun Context.getFileDescriptor(uri: Uri): FileDescriptor {
        return contentResolver.openFileDescriptor(uri, "r")!!.fileDescriptor
    }

    @Throws(IOException::class)
    private fun Context.getBitmapFromUri(
        uri: Uri,
        options: BitmapFactory.Options? = null
    ): Bitmap? {
        val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        val fileDescriptor = parcelFileDescriptor?.fileDescriptor
        val image: Bitmap? = if (options != null)
            BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options)
        else
            BitmapFactory.decodeFileDescriptor(fileDescriptor)
        parcelFileDescriptor?.close()
        return image
    }

    companion object {

        fun create(context: Context, init: Builder.() -> Unit) = Builder(context, init).build()
    }
}