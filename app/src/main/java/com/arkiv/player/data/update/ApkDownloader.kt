package com.arkiv.player.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val error: String) : DownloadState
}

class ApkDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun download(url: String): Flow<DownloadState> = flow {
        val dest = File(context.cacheDir, "update.apk")
        if (dest.exists()) dest.delete()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            emit(DownloadState.Failed("HTTP ${response.code}"))
            return@flow
        }
        val body = response.body ?: run {
            emit(DownloadState.Failed("Empty response"))
            return@flow
        }
        val total = body.contentLength()
        var downloaded = 0L
        dest.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    emit(DownloadState.Downloading(progress))
                }
            }
        }
        emit(DownloadState.Ready(dest))
    }.flowOn(Dispatchers.IO)
}
