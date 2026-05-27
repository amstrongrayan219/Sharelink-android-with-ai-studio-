package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import com.example.data.AppDatabase
import com.example.data.Device
import com.example.ui.theme.MyApplicationTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApplicationTheme {
                ScannerScreen(
                    onBack = { finish() },
                    executor = cameraExecutor,
                    onNavigateToTransfer = { device ->
                        // Redirige vers TransferActivity
                        val intent = Intent(this, TransferActivity::class.java).apply {
                            putExtra("target_name", device.name)
                            putExtra("target_ip", device.ip)
                            putExtra("target_http_port", device.httpPort)
                            putExtra("target_udp_port", device.udpPort)
                        }
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    executor: ExecutorService,
    onNavigateToTransfer: (Device) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "La permission Caméra est requise pour scanner les QR Codes.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // Scanned device state
    var detectedDevice by remember { mutableStateOf<Device?>(null) }
    var showPopup by remember { mutableStateOf(false) }
    var isScanningActive by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scanner_activity_container")
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = previewView.surfaceProvider
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val analyzer = ImageAnalysis.Analyzer { imageProxy ->
                            if (!isScanningActive || detectedDevice != null) {
                                imageProxy.close()
                                return@Analyzer
                            }

                            processImageProxy(imageProxy) { name, ip, httpPort, udpPort ->
                                // QR code scanned successfully! Stop scanning, show popup
                                isScanningActive = false
                                detectedDevice = Device(
                                    name = name,
                                    ip = ip,
                                    httpPort = httpPort,
                                    udpPort = udpPort
                                )
                                showPopup = true
                            }
                        }

                        imageAnalysis.setAnalyzer(executor, analyzer)

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (exc: Exception) {
                            Log.e("ScannerActivity", "Use case binding failed", exc)
                        }

                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("En attente de la permission d'accès à la caméra...", color = Color.White)
            }
        }

        // Animated laser scan sweep
        ScannerOverlay()

        // Absolute top bar with back navigation over preview view
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .testTag("scanner_back_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Retour",
                    tint = Color.White
                )
            }
        }

        // Popup Dialog immediately showing scanned details
        if (showPopup && detectedDevice != null) {
            DeviceDetectedDialog(
                device = detectedDevice!!,
                onDismiss = {
                    showPopup = false
                    detectedDevice = null
                    isScanningActive = true
                },
                onConnect = {
                    coroutineScope.launch {
                        // Persist or save database history
                        val db = AppDatabase.getDatabase(context)
                        withContext(Dispatchers.IO) {
                            db.deviceDao().deleteDeviceByIp(detectedDevice!!.ip)
                            db.deviceDao().insertDevice(detectedDevice!!)
                        }
                        withContext(Dispatchers.Main) {
                            onNavigateToTransfer(detectedDevice!!)
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ScannerOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "laser_anim")
    val sweepPosition by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val reticleWidth = width * 0.65f
        val reticleHeight = width * 0.65f

        val left = (width - reticleWidth) / 2f
        val top = (height - reticleHeight) / 2f
        val right = left + reticleWidth
        val bottom = top + reticleHeight

        // Dark dim overlay outside of the scanner bounding box for high-readability
        drawRect(
            color = Color.Black.copy(alpha = 0.6f),
            size = size
        )

        // Clear out the center box to see the preview
        drawRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(reticleWidth, reticleHeight),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear
        )

        // Draw bounding box white frame borders
        val cornerLen = 24.dp.toPx()
        val strokeW = 4.dp.toPx()
        val cyanColor = Color(0xFF1565C0)

        // Top-Left corner bracket
        drawLine(cyanColor, Offset(left - strokeW / 2, top), Offset(left + cornerLen, top), strokeW)
        drawLine(cyanColor, Offset(left, top - strokeW / 2), Offset(left, top + cornerLen), strokeW)

        // Top-Right corner bracket
        drawLine(cyanColor, Offset(right + strokeW / 2, top), Offset(right - cornerLen, top), strokeW)
        drawLine(cyanColor, Offset(right, top - strokeW / 2), Offset(right, top + cornerLen), strokeW)

        // Bottom-Left corner bracket
        drawLine(cyanColor, Offset(left - strokeW / 2, bottom), Offset(left + cornerLen, bottom), strokeW)
        drawLine(cyanColor, Offset(left, bottom + strokeW / 2), Offset(left, bottom - cornerLen), strokeW)

        // Bottom-Right corner bracket
        drawLine(cyanColor, Offset(right + strokeW / 2, bottom), Offset(right - cornerLen, bottom), strokeW)
        drawLine(cyanColor, Offset(right, bottom + strokeW / 2), Offset(right, bottom - cornerLen), strokeW)

        // Pulsating glowing red/blue scanning sweep laser line
        val scanY = top + (bottom - top) * sweepPosition
        drawLine(
            cyanColor,
            Offset(left + 8f, scanY),
            Offset(right - 8f, scanY),
            strokeWidth = 3.dp.toPx()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "Ciblez le QR Code de votre PC",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun DeviceDetectedDialog(
    device: Device,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Appareil détecté",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Metadata list
                DeviceMetaItem("Nom :", device.name)
                Spacer(modifier = Modifier.height(8.dp))
                DeviceMetaItem("IP du PC :", device.ip)
                Spacer(modifier = Modifier.height(8.dp))
                DeviceMetaItem("Port HTTP :", device.httpPort.toString())

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("dismiss_detected_button")
                    ) {
                        Text("Annuler", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("connect_detected_button")
                    ) {
                        Text("Connecter", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceMetaItem(label: String, valStr: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(valStr, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    onSuccess: (String, String, Int, Int) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val content = barcode.rawValue ?: continue
                    try {
                        val jsonObj = JSONObject(content)
                        if (jsonObj.has("name") && jsonObj.has("ip")) {
                            val name = jsonObj.getString("name")
                            val ip = jsonObj.getString("ip")
                            val httpPort = jsonObj.optInt("http_port", 8080)
                            val udpPort = jsonObj.optInt("udp_port", 1234)

                            onSuccess(name, ip, httpPort, udpPort)
                            break
                        }
                    } catch (e: Exception) {
                        // Not a valid JSON or doesn't match keys, ignore
                        Log.d("ScannerActivity", "Invalid QR content: $content")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("ScannerActivity", "Barcode scanning failure", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
