package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlin.math.min

class ShareHttpService : Service() {

    private var server: ShareServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            server = ShareServer(8080, applicationContext)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d("ShareHttpService", "NanoHTTPD started on port 8080 successfully.")
        } catch (e: Exception) {
            Log.e("ShareHttpService", "Failed to start HTTP server: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e("ShareHttpService", "Error stopping server: ${e.message}")
        }
        Log.d("ShareHttpService", "Service destroyed, server stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private class ShareServer(port: Int, private val context: Context) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d("ShareServer", "Received request: $method $uri")

            if (uri == "/request-transfer" && method == Method.POST) {
                val response = "{\"accepted\":true}"
                return newFixedLengthResponse(Response.Status.OK, "application/json", response)
            }

            if (uri == "/upload" && method == Method.POST) {
                return try {
                    val headers = session.headers
                    val filename = headers["x-filename"] ?: "upload_${System.currentTimeMillis()}"
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

                    val targetDir = context.getExternalFilesDir("ShareLink") ?: File(context.filesDir, "ShareLink")
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val targetFile = File(targetDir, filename)

                    val inputStream = session.inputStream
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (totalRead < contentLength) {
                            val toRead = minOf(buffer.size.toLong(), contentLength - totalRead).toInt()
                            bytesRead = inputStream.read(buffer, 0, toRead)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                    }

                    // Refresh file list state inside state flow
                    ShareServerEvents.notifyFileReceived(context)

                    val response = "{\"success\":true}"
                    newFixedLengthResponse(Response.Status.OK, "application/json", response)
                } catch (e: Exception) {
                    Log.e("ShareServer", "Error receiving file: ${e.message}")
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        "{\"success\":false,\"error\":\"${e.message}\"}"
                    )
                }
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
