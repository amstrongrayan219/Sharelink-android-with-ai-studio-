package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class IncomingRequest(
    val from: String,
    val files: List<String>,
    val future: CompletableFuture<Boolean>
)

data class ReceivedFile(
    val name: String,
    val path: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface SendingState {
    object Idle : SendingState
    object Requesting : SendingState
    data class Sending(val currentFile: String, val progress: Float, val currentIndex: Int, val totalFiles: Int) : SendingState
    data class Success(val message: String) : SendingState
    data class Error(val message: String) : SendingState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("sharelink_prefs", Context.MODE_PRIVATE)

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isSetupCompleted = MutableStateFlow(false)
    val isSetupCompleted: StateFlow<Boolean> = _isSetupCompleted.asStateFlow()

    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _activeIncomingRequest = MutableStateFlow<IncomingRequest?>(null)
    val activeIncomingRequest: StateFlow<IncomingRequest?> = _activeIncomingRequest.asStateFlow()

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState.asStateFlow()

    private val _receivedFilesHistory = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFilesHistory: StateFlow<List<ReceivedFile>> = _receivedFilesHistory.asStateFlow()

    private val _selectedDeviceForTransfer = MutableStateFlow<DiscoveredDevice?>(null)
    val selectedDeviceForTransfer: StateFlow<DiscoveredDevice?> = _selectedDeviceForTransfer.asStateFlow()

    private val discoveryService = DiscoveryService()
    private var httpServer: ShareServer? = null
    private val serverMutex = Mutex()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "MainViewModel"
        private const val KEY_DEVICE_NAME = "key_device_name"
        private const val SERVER_PORT = 8080
    }

    init {
        loadPreferences()
        updateLocalIp()
        loadReceivedHistory()
        
        // Démarrer des coroutines de nettoyage des appareils hors-ligne
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5000)
                val limit = System.currentTimeMillis() - 10000 // Inactif depuis 10 secondes
                val list = _discoveredDevices.value
                val filtered = list.filter { it.lastSeen >= limit }
                if (filtered.size != list.size) {
                    _discoveredDevices.value = filtered
                    Log.d(TAG, "Nettoyage des appareils inactifs. Restants: ${filtered.size}")
                }
            }
        }
    }

    private fun loadPreferences() {
        val savedName = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        if (savedName.isNotBlank()) {
            _deviceName.value = savedName
            _isSetupCompleted.value = true
            startServerAndDiscovery()
        } else {
            // Nom par défaut
            val model = android.os.Build.MODEL ?: "Android Device"
            _deviceName.value = model
            _isSetupCompleted.value = false
        }
    }

    fun saveDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
            _deviceName.value = trimmed
            _isSetupCompleted.value = true
            startServerAndDiscovery()
        }
    }

    fun updateLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = getWifiIpAddress()
            _localIp.value = ip
        }
    }

    private fun getWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                val addresses = networkInterface.inetAddresses
                for (address in Collections.list(addresses)) {
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la capture de l'IP", e)
        }
        return "127.0.0.1"
    }

    fun startServerAndDiscovery() {
        if (_deviceName.value.isBlank()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            serverMutex.withLock {
                if (_isServerRunning.value && httpServer != null) {
                    Log.d(TAG, "Le serveur et la détection tournent déjà. Ignoré.")
                    return@launch
                }

                val ip = getWifiIpAddress()
                _localIp.value = ip

                // S'assurer de stopper l'ancien serveur s'il existait de manière sécurisée en arrière-plan
                try {
                    httpServer?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur arrêt http server", e)
                }
                httpServer = null

                try {
                    // Créer et démarrer le serveur de réception HTTP
                    val server = ShareServer(
                        context = context,
                        port = SERVER_PORT,
                        onRequestTransfer = { from, files ->
                            val future = CompletableFuture<Boolean>()
                            _activeIncomingRequest.value = IncomingRequest(from, files, future)
                            future
                        },
                        onFileReceived = { filename, filepath ->
                            onIncomingFileWritten(filename, filepath)
                        }
                    )
                    server.start()
                    httpServer = server
                    _isServerRunning.value = true
                    Log.d(TAG, "Serveur HTTP ShareLink démarré sur le port $SERVER_PORT")

                    // Démarrer la détection UDP
                    discoveryService.startListening { device ->
                        // Éviter de s'enregistrer soi-même
                        val currentIp = _localIp.value
                        if (device.ip != currentIp) {
                            val currentList = _discoveredDevices.value
                            val index = currentList.indexOfFirst { it.ip == device.ip }
                            if (index != -1) {
                                // Mettre à jour l'appareil existant
                                val updated = currentList.toMutableList().apply {
                                    set(index, device)
                                }
                                _discoveredDevices.value = updated
                            } else {
                                // Ajouter le nouvel appareil
                                _discoveredDevices.value = currentList + device
                                Log.d(TAG, "Nouvel appareil détecté: ${device.name} @ ${device.ip}")
                            }
                        }
                    }

                    // Commencer à diffuser sa présence en UDP
                    discoveryService.startBroadcasting(_deviceName.value, ip, SERVER_PORT)

                } catch (e: Exception) {
                    _isServerRunning.value = false
                    Log.e(TAG, "Erreur lors du démarrage du serveur HTTP ou UDP", e)
                }
            }
        }
    }

    fun respondToIncomingRequest(accept: Boolean) {
        val activeReq = _activeIncomingRequest.value
        if (activeReq != null) {
            activeReq.future.complete(accept)
            _activeIncomingRequest.value = null
        }
    }

    private fun onIncomingFileWritten(filename: String, filepath: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val record = ReceivedFile(filename, filepath)
            val currentHistory = _receivedFilesHistory.value
            _receivedFilesHistory.value = listOf(record) + currentHistory
            saveReceivedHistory()
        }
    }

    private fun loadReceivedHistory() {
        // Optionnel : persister via un petit fichier texte interne ou SharedPreferences
        val historyStr = prefs.getString("received_history_json", null)
        if (historyStr != null) {
            try {
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, ReceivedFile::class.java)
                val adapter = moshi.adapter<List<ReceivedFile>>(type)
                val loaded = adapter.fromJson(historyStr)
                if (loaded != null) {
                    _receivedFilesHistory.value = loaded
                }
            } catch (e: Exception) {
                Log.e(TAG, "Échec lors du chargement de l'historique", e)
            }
        }
    }

    private fun saveReceivedHistory() {
        try {
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, ReceivedFile::class.java)
            val adapter = moshi.adapter<List<ReceivedFile>>(type)
            val json = adapter.toJson(_receivedFilesHistory.value)
            prefs.edit().putString("received_history_json", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Échec lors de la sauvegarde de l'historique", e)
        }
    }

    fun clearReceivedHistory() {
        _receivedFilesHistory.value = emptyList()
        prefs.edit().remove("received_history_json").apply()
    }

    fun selectDeviceForTransfer(device: DiscoveredDevice?) {
        _selectedDeviceForTransfer.value = device
        _sendingState.value = SendingState.Idle
    }

    fun sendFilesToSelectedDevice(fileUris: List<Uri>) {
        val targetDevice = _selectedDeviceForTransfer.value
        if (targetDevice == null) {
            _sendingState.value = SendingState.Error("Aucun appareil destinataire sélectionné.")
            return
        }

        if (fileUris.isEmpty()) {
            _sendingState.value = SendingState.Error("Veuillez sélectionner au moins un fichier.")
            return
        }

        _sendingState.value = SendingState.Requesting

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Étape 1 : Récupérer les métadonnées de tous les fichiers
                val filesToSend = fileUris.map { uri ->
                    getFileMetadata(uri)
                }

                val fileNames = filesToSend.map { it.name }

                // Étape 2 : Envoyer une demande de transfert (POST /request-transfer)
                val requestTransferUrl = "http://${targetDevice.ip}:${targetDevice.port}/request-transfer"
                
                val requestPayload = mapOf(
                    "from" to _deviceName.value,
                    "files" to fileNames
                )
                val requestAdapter = moshi.adapter(Map::class.java)
                val jsonBody = requestAdapter.toJson(requestPayload)

                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                
                Log.d(TAG, "Envoi demande de transfert à $requestTransferUrl")
                val request = Request.Builder()
                    .url(requestTransferUrl)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "Réponse de demande de transfert reçue: $responseBodyStr")

                if (!response.isSuccessful) {
                    _sendingState.value = SendingState.Error("L'appareil distant a renvoyé une erreur de demande: ${response.code}")
                    return@launch
                }

                // Parser le choix de l'utilisateur distant
                val responseMap = try {
                    val adapter = moshi.adapter(Map::class.java)
                    adapter.fromJson(responseBodyStr) as? Map<String, Any>
                } catch (e: Exception) {
                    null
                }

                val status = responseMap?.get("status") as? String
                if (status != "accepted") {
                    _sendingState.value = SendingState.Error("Le transfert a été refusé par l'appareil distant.")
                    return@launch
                }

                // Étape 3 : Envoyer chaque fichier un par un (POST /upload)
                filesToSend.forEachIndexed { index, fileMeta ->
                    _sendingState.value = SendingState.Sending(
                        currentFile = fileMeta.name,
                        progress = 0f,
                        currentIndex = index + 1,
                        totalFiles = filesToSend.size
                    )

                    val uploadUrl = "http://${targetDevice.ip}:${targetDevice.port}/upload"

                    val inputMediaStream = context.contentResolver.openInputStream(fileMeta.uri)
                    if (inputMediaStream == null) {
                        _sendingState.value = SendingState.Error("Impossible de lire le fichier: ${fileMeta.name}")
                        return@launch
                    }

                    // RequestBody customisé qui remplit les octets tout en surveillant le débit
                    val rawBody = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = fileMeta.size

                        override fun writeTo(sink: BufferedSink) {
                            val buffer = ByteArray(1024 * 32)
                            var totalBytesWritten = 0L
                            inputMediaStream.use { input ->
                                var bytesRead = input.read(buffer)
                                while (bytesRead >= 0) {
                                    sink.write(buffer, 0, bytesRead)
                                    totalBytesWritten += bytesRead
                                    
                                    // Mettre à jour la progression de manière fluide
                                    val pct = if (fileMeta.size > 0) totalBytesWritten.toFloat() / fileMeta.size else 0.5f
                                    _sendingState.value = SendingState.Sending(
                                        currentFile = fileMeta.name,
                                        progress = pct.coerceIn(0f, 1f),
                                        currentIndex = index + 1,
                                        totalFiles = filesToSend.size
                                    )
                                    bytesRead = input.read(buffer)
                                }
                            }
                        }
                    }

                    val uploadRequest = Request.Builder()
                        .url(uploadUrl)
                        .header("x-filename", fileMeta.name)
                        .post(rawBody)
                        .build()

                    val uploadResponse = httpClient.newCall(uploadRequest).execute()
                    val uploadResultStr = uploadResponse.body?.string() ?: ""
                    Log.d(TAG, "Réponse d'upload pour ${fileMeta.name}: $uploadResultStr")

                    if (!uploadResponse.isSuccessful) {
                        _sendingState.value = SendingState.Error("Échec lors de l'upload du fichier '${fileMeta.name}': ${uploadResponse.code}")
                        return@launch
                    }
                }

                // Succès complet
                _sendingState.value = SendingState.Success("Excellent ! ${filesToSend.size} fichier(s) ont été envoyés avec succès.")

            } catch (e: IOException) {
                Log.e(TAG, "Erreur réseau d'envoi", e)
                _sendingState.value = SendingState.Error("Erreur de connexion réseau : ${e.localizedMessage}")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de l'envoi", e)
                _sendingState.value = SendingState.Error("Erreur imprévue : ${e.localizedMessage}")
            }
        }
    }

    private fun getFileMetadata(uri: Uri): FileMetadata {
        var name = "unknown_file_${System.currentTimeMillis()}"
        var size = -1L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        val n = cursor.getString(nameIndex)
                        if (!n.isNullOrBlank()) name = n
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur metadata du fichier", e)
        }

        // Si la taille est d'une manière ou d'une autre indéterminée, on essaie d'ouvrir un descripteur
        if (size <= 0) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    size = afd.length
                }
            } catch (e: Exception) {
                // Laisser à -1 ou taille approximative
            }
        }

        return FileMetadata(uri, name, size)
    }

    override fun onCleared() {
        super.onCleared()
        stopAllServices()
    }

    fun stopAllServices() {
        cleanupScope.launch(Dispatchers.IO) {
            discoveryService.stop()
            try {
                httpServer?.stop()
            } catch (e: Exception) {
                // Ignorer
            }
            httpServer = null
            _isServerRunning.value = false
        }
    }
}

data class FileMetadata(
    val uri: Uri,
    val name: String,
    val size: Long
)
