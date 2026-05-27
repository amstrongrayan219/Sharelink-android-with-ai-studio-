package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.log10

class TransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val targetName = intent.getStringExtra("target_name") ?: "PC Share"
        val targetIp = intent.getStringExtra("target_ip") ?: ""
        val targetHttpPort = intent.getIntExtra("target_http_port", 8080)
        val targetUdpPort = intent.getIntExtra("target_udp_port", 1234)

        setContent {
            MyApplicationTheme {
                TransferScreen(
                    targetName = targetName,
                    targetIp = targetIp,
                    targetHttpPort = targetHttpPort,
                    targetUdpPort = targetUdpPort,
                    onBack = { finish() }
                )
            }
        }
    }
}

data class FileDetail(
    val uri: Uri,
    val name: String,
    val size: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    targetName: String,
    targetIp: String,
    targetHttpPort: Int,
    targetUdpPort: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // File transfer state
    var selectedFiles by remember { mutableStateOf<List<FileDetail>>(emptyList()) }
    val receivedFiles by ShareServerEvents.receivedFilesFlow.collectAsState(initial = emptyList())

    // Upload lifecycle progress flow state
    var isSending by remember { mutableStateOf(false) }
    var filesTotal by remember { mutableStateOf(0) }
    var filesSent by remember { mutableStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var currentFileSizeString by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Trigger initial scan for received files on launch
    LaunchedEffect(Unit) {
        ShareServerEvents.notifyFileReceived(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.map { uri ->
                resolveUriDetails(context, uri)
            }
            errorMessage = null
        }
    }

    // Animated connection glowing indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2E7D32).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Connecté à : $targetName",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                        Text(
                            text = "Hôte distant (PC) : $targetIp:$targetHttpPort",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("transfer_back_button")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF04060C))
                    )
                )
                .padding(16.dp)
        ) {
            // ================= SECTION 1: SEND ACTIONS =================
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ENVOYER",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF1565C0),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!isSending) {
                                    filePickerLauncher.launch("*/*")
                                }
                            },
                            enabled = !isSending,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("btn_add_files")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ajouter fichiers", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (selectedFiles.isNotEmpty() && !isSending) {
                                    coroutineScope.launch {
                                        performFileUpload(
                                            context = context,
                                            targetIp = targetIp,
                                            targetHttpPort = targetHttpPort,
                                            selectedFiles = selectedFiles,
                                            onStart = {
                                                isSending = true
                                                filesTotal = selectedFiles.size
                                                filesSent = 0
                                                errorMessage = null
                                            },
                                            onFileProgress = { idx, name, sizeStr ->
                                                currentFileName = name
                                                currentFileSizeString = sizeStr
                                            },
                                            onFileSuccess = {
                                                filesSent++
                                            },
                                            onComplete = {
                                                isSending = false
                                                selectedFiles = emptyList()
                                                Toast.makeText(context, "Tous les fichiers ont été envoyés !", Toast.LENGTH_SHORT).show()
                                            },
                                            onFailure = { err ->
                                                isSending = false
                                                errorMessage = err
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = selectedFiles.isNotEmpty() && !isSending,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("btn_send_files")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Envoyer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    // Progress indicators
                    if (isSending) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0F172A))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Fichier ${filesSent + 1} / $filesTotal",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1565C0)
                                )
                                Text(
                                    text = currentFileSizeString,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = currentFileName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (filesTotal > 0) filesSent.toFloat() / filesTotal.toFloat() else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF1565C0),
                                trackColor = Color(0xFF334155),
                            )
                        }
                    }

                    // Error display banner
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFB71C1C).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFB71C1C), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFB71C1C))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "Erreur inconnue",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Selected files queue preview
                    if (selectedFiles.isNotEmpty() && !isSending) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            "Fichiers prêts à l'envoi :",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 100.dp)
                        ) {
                            selectedFiles.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F172A))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = file.name,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatBytes(file.size),
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8),
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================= SECTION 2: RECEIVE LAYOUT =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "RECEVOIR (DOSSIER LOCAL)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF2E7D32),
                    letterSpacing = 1.sp
                )

                // Button to Open local Received folder in explorer
                TextButton(
                    onClick = {
                        openShareLinkExplorerFolder(context)
                    },
                    modifier = Modifier.testTag("btn_open_folder")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF2E7D32))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ouvrir dossier", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scroll list of received files
            if (receivedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.3f))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Aussi actif en réception",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Envoyez des fichiers directement depuis le PC, ils apparaitront ici en temps réel.",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("received_files_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(receivedFiles) { file ->
                        ReceivedFileItemRow(file)
                    }
                }
            }
        }
    }
}

@Composable
fun ReceivedFileItemRow(file: ReceivedFile) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
            .clickable {
                // Clicking opens the file directly!
                openReceivedFile(context, file.absolutePath)
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = file.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Taille : ${formatBytes(file.size)}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                text = formatTime(file.time),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

// Resilient file intent explorer opener
fun openShareLinkExplorerFolder(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            val targetDir = context.getExternalFilesDir("ShareLink") ?: File(context.filesDir, "ShareLink")
            setDataAndType(Uri.fromFile(targetDir), "*/*")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "*/*"
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            Toast.makeText(context, "Impossible d'ouvrir l'explorateur automatiquement.", Toast.LENGTH_SHORT).show()
        }
    }
}

// Open individual received files
fun openReceivedFile(context: Context, path: String) {
    try {
        val file = File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Aperçu indisponible. Ouvrez via votre gestionnaire de fichiers.", Toast.LENGTH_LONG).show()
    }
}

// Resilient ContentProvider Uri file metadata resolver
fun resolveUriDetails(context: Context, uri: Uri): FileDetail {
    var name = "Fichier_sans_nom"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    if (name == "Fichier_sans_nom") {
        name = uri.lastPathSegment ?: "Fichier_${System.currentTimeMillis()}"
    }
    return FileDetail(uri, name, size)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 o"
    val units = arrayOf("o", "Ko", "Mo", "Go")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

// Background thread upload tasks executing sequencial POST endpoints
suspend fun performFileUpload(
    context: Context,
    targetIp: String,
    targetHttpPort: Int,
    selectedFiles: List<FileDetail>,
    onStart: () -> Unit,
    onFileProgress: (Int, String, String) -> Unit,
    onFileSuccess: () -> Unit,
    onComplete: () -> Unit,
    onFailure: (String) -> Unit
) {
    withContext(Dispatchers.Main) {
        onStart()
    }

    try {
        val prefs = context.getSharedPreferences("sharelink_prefs", Context.MODE_PRIVATE)
        val myName = prefs.getString("device_name", "Téléphone") ?: "Téléphone"

        // Step 1 Payload
        val fileNamesJson = JSONArray()
        selectedFiles.forEach { fileNamesJson.put(it.name) }

        val requestPayload = JSONObject().apply {
            put("device_name", myName)
            put("files", fileNamesJson)
            put("port", 8080)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.MINUTES) // Supports large files!
            .readTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
            .build()

        val jsonBody = requestPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val step1Request = Request.Builder()
            .url("http://$targetIp:$targetHttpPort/request-transfer")
            .post(jsonBody)
            .build()

        val accepted = withContext(Dispatchers.IO) {
            client.newCall(step1Request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Le serveur PC a retourné une erreur HTTP ${response.code}")
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                json.optBoolean("accepted", false)
            }
        }

        if (!accepted) {
            throw Exception("Le transfert a été rejeté par le PC.")
        }

        // Step 2 uploads
        for (i in selectedFiles.indices) {
            val file = selectedFiles[i]

            withContext(Dispatchers.Main) {
                onFileProgress(i, file.name, formatBytes(file.size))
            }

            val requestBody = object : RequestBody() {
                override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                override fun contentLength(): Long = file.size

                override fun writeTo(sink: okio.BufferedSink) {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            val uploadRequest = Request.Builder()
                .url("http://$targetIp:$targetHttpPort/upload")
                .header("x-filename", file.name)
                .post(requestBody)
                .build()

            val success = withContext(Dispatchers.IO) {
                client.newCall(uploadRequest).execute().use { response ->
                    if (!response.isSuccessful) false
                    else {
                        val bodyStr = response.body?.string() ?: ""
                        try {
                            JSONObject(bodyStr).optBoolean("success", false)
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            }

            if (!success) {
                throw Exception("Erreur d'envoi pour : ${file.name}")
            }

            withContext(Dispatchers.Main) {
                onFileSuccess()
            }
        }

        withContext(Dispatchers.Main) {
            onComplete()
        }

    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onFailure(e.message ?: "Échec de connexion ou d'écriture.")
        }
    }
}
