package com.example

import android.content.Context
import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ShareServer(
    private val context: Context,
    port: Int = 8080,
    private val onRequestTransfer: (from: String, files: List<String>) -> CompletableFuture<Boolean>,
    private val onFileReceived: (filename: String, filepath: String) -> Unit
) : NanoHTTPD(port) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    companion object {
        private const val TAG = "ShareServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "Reçu requête: $method $uri de ${session.remoteIpAddress}")

        return when {
            uri == "/request-transfer" && method == Method.POST -> {
                handleRequestTransfer(session)
            }
            uri == "/upload" && method == Method.POST -> {
                handleUpload(session)
            }
            else -> {
                val jsonResponse = "{\"error\": \"Route non trouvée\"}"
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", jsonResponse)
            }
        }
    }

    private fun handleRequestTransfer(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val jsonBody = files["postData"]
            if (jsonBody.isNullOrBlank()) {
                Log.e(TAG, "Corps de requête vide pour /request-transfer")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Requete vide\"}")
            }

            Log.d(TAG, "Données de transfert reçues: $jsonBody")
            // Parser le JSON manuellement ou via Moshi pour rester ultra robuste
            val requestMap = try {
                val adapter = moshi.adapter(Map::class.java)
                adapter.fromJson(jsonBody) as? Map<String, Any>
            } catch (e: Exception) {
                null
            }

            if (requestMap == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"JSON invalide\"}")
            }

            val fromUser = requestMap["from"] as? String ?: "PC Distant"
            val fileListRaw = requestMap["files"] as? List<*>
            val fileList = fileListRaw?.filterIsInstance<String>() ?: emptyList()

            // Lancer la demande de permission utilisateur
            val future = onRequestTransfer(fromUser, fileList)

            // Attendre le choix de l'utilisateur avec un timeout de 45 secondes
            val decision = try {
                future.get(45, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Timeout ou annulation de la permission de transfert", e)
                false
            }

            if (decision) {
                Log.d(TAG, "Transfert accepté par l'utilisateur d'Android")
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"accepted\"}")
            } else {
                Log.d(TAG, "Transfert décliné par l'utilisateur d'Android")
                // Utiliser Status.FORBIDDEN (403) ou OK avec statut décliné
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"declined\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de handleRequestTransfer", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"${e.localizedMessage}\"}")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        var tempFileToDelete: File? = null
        try {
            val headers = session.headers
            val filename = headers["x-filename"] ?: "fichier_${System.currentTimeMillis()}"
            Log.d(TAG, "Début de l'envoi de fichier: $filename")

            // On résout le dossier de réception sécurisé
            val targetDir = getUploadsDirectory()
            val destinationFile = File(targetDir, filename)

            val files = HashMap<String, String>()
            session.parseBody(files)

            val tmpFilePath = files["postData"] ?: files["content"]
            if (!tmpFilePath.isNullOrBlank()) {
                val tmpFile = File(tmpFilePath)
                tempFileToDelete = tmpFile
                
                // Copier le contenu
                tmpFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                Log.d(TAG, "Fichier enregistré avec succès dans: ${destinationFile.absolutePath}")
                onFileReceived(filename, destinationFile.absolutePath)
                
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"success\", \"path\": \"${destinationFile.absolutePath}\"}")
            } else {
                // Fallback de streaming si postData est vide (ex: flux brut direct)
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                if (contentLength > 0) {
                    val stream = session.inputStream
                    var bytesCopied = 0L
                    val buffer = ByteArray(1024 * 64)
                    
                    destinationFile.outputStream().use { output ->
                        var bytes = stream.read(buffer)
                        while (bytes >= 0 && bytesCopied < contentLength) {
                            output.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            if (bytesCopied >= contentLength) break
                            bytes = stream.read(buffer)
                        }
                    }
                    Log.d(TAG, "Fichier streamé enregistré avec succès dans: ${destinationFile.absolutePath}")
                    onFileReceived(filename, destinationFile.absolutePath)
                    
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"success\", \"path\": \"${destinationFile.absolutePath}\"}")
                }
            }

            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Fichier ou données d'envoi vides\"}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la réception de l'envoi /upload: ${e.message}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"${e.localizedMessage}\"}")
        } finally {
            try {
                tempFileToDelete?.delete()
            } catch (e: Exception) {
                // Ignorer
            }
        }
    }

    private fun getUploadsDirectory(): File {
        // Chemin public préféré: Téléchargements/ShareLink
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ShareLink")
        return try {
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            if (publicDir.canWrite()) {
                publicDir
            } else {
                // Essai d'écriture de test
                val testFile = File(publicDir, ".test_write")
                testFile.createNewFile()
                testFile.delete()
                publicDir
            }
        } catch (e: Exception) {
            // Fallback privé sécurisé en cas de non-autorisé
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ShareLink")
            if (!fallbackDir.exists()) {
                fallbackDir.mkdirs()
            }
            fallbackDir
        }
    }
}
