package com.hanyajasa.webcam150226

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

// Alamat IP publik VPS Anda
private const val WAN_SERVER_IP = "194.233.84.144"
private const val WAN_SERVER_PORT = "8080" // Asumsi port yang sama, bisa diubah
private const val WAN_SERVER_URL = "http://$WAN_SERVER_IP:$WAN_SERVER_PORT/stream"

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // Sebaiknya tangani jika user menolak izin kamera
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            StreamingChoiceScreen()
        }
    }
}

enum class StreamingMode {
    NONE, LAN, WAN
}

@Composable
fun StreamingChoiceScreen() {
    var streamingMode by remember { mutableStateOf(StreamingMode.NONE) }

    when (streamingMode) {
        StreamingMode.NONE -> {
            ModeSelector(
                onLanClick = { streamingMode = StreamingMode.LAN },
                onWanClick = { streamingMode = StreamingMode.WAN }
            )
        }
        else -> {
            StreamingScreen(mode = streamingMode)
        }
    }
}

@Composable
fun ModeSelector(onLanClick: () -> Unit, onWanClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Pilih Mode Streaming", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onLanClick) {
            Text("Mode LAN (Server di HP)")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onWanClick) {
            Text("Mode WAN (Kirim ke VPS)")
        }
    }
}

@Composable
fun StreamingScreen(mode: StreamingMode) {
    val imageFlow = remember { MutableStateFlow<ByteArray?>(null) }
    val context = LocalContext.current
    var ipAddress by remember { mutableStateOf("Detecting IP...") }
    var wanStatus by remember { mutableStateOf("Idle") }

    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ip = linkProperties?.linkAddresses?.find { it.address.hostAddress?.contains('.') == true }?.address?.hostAddress
        ipAddress = ip ?: "Not connected to WiFi"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(imageFlow)

        when(mode) {
            StreamingMode.LAN -> {
                LanStreamer(imageFlow = imageFlow)
                Text(
                    text = "Stream at: http://$ipAddress:8080/video",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = Color.White
                )
            }
            StreamingMode.WAN -> {
                WanUploader(imageFlow = imageFlow, onStatusChange = { wanStatus = it })
                Text(
                    text = """WAN Mode: $wanStatus
Sending to: $WAN_SERVER_URL""",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = Color.White
                )
            }
            else -> {}
        }
    }
}

@Composable
fun LanStreamer(imageFlow: StateFlow<ByteArray?>) {
    val server = remember {
        embeddedServer(Netty, port = 8080) {
            routing {
                get("/video") {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            imageFlow.collectLatest { imageBytes ->
                                if (isActive && imageBytes != null) {
                                    channel.writeStringUtf8("--frame\n")
                                    channel.writeStringUtf8("Content-Type: image/jpeg\n")
                                    channel.writeStringUtf8("Content-Length: ${imageBytes.size}\n")
                                    channel.writeStringUtf8("\n")
                                    channel.writeFully(imageBytes)
                                    channel.writeStringUtf8("\n")
                                    channel.flush()
                                }
                            }
                        }
                    })
                }
            }
        }.start(wait = false)
    }

    DisposableEffect(Unit) {
        onDispose {
            server.stop(1_000, 2_000)
        }
    }
}

@Composable
fun WanUploader(imageFlow: StateFlow<ByteArray?>, onStatusChange: (String) -> Unit) {
    val client = remember { HttpClient(CIO) }

    DisposableEffect(Unit) {
        onDispose {
            client.close()
        }
    }

    // LaunchedEffect will run this coroutine and keep it alive.
    // We use .collect to process every frame sequentially.
    LaunchedEffect(Unit) {
        imageFlow.collect { imageBytes ->
            // Only proceed if we have a valid image byte array.
            if (imageBytes != null) {
                // We use withContext to switch to a background thread for the network call,
                // without blocking the UI thread. The collect loop will wait for this
                // block to complete before processing the next frame.
                withContext(Dispatchers.IO) {
                    try {
                        onStatusChange("Uploading...")
                        val response: HttpResponse = client.post(WAN_SERVER_URL) {
                            setBody(imageBytes)
                            contentType(ContentType.Image.JPEG)
                        }
                        // Check if the server responded with OK
                        if (response.status == HttpStatusCode.OK) {
                            onStatusChange("Frame sent")
                        } else {
                            onStatusChange("Server error: ${response.status}")
                        }
                    } catch (e: Exception) {
                        // In case of network errors, timeouts, etc.
                        onStatusChange("Error: ${e.message}")
                    }
                }
            }
        }
    }
}


@Composable
fun CameraPreview(imageFlow: MutableStateFlow<ByteArray?>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val yBuffer = imageProxy.planes[0].buffer
                val uBuffer = imageProxy.planes[1].buffer
                val vBuffer = imageProxy.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
                val imageBytes = out.toByteArray()
                imageFlow.value = imageBytes

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                // Log error
            }

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
