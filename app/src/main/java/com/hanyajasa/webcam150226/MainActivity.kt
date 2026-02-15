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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted. Continue the action or workflow in your app.
        } else {
            // Explain to the user that the feature is unavailable
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
            WebServerScreen()
        }
    }
}

@Composable
fun WebServerScreen() {
    val context = LocalContext.current
    var ipAddress by remember { mutableStateOf("Detecting IP...") }

    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ip = linkProperties?.linkAddresses?.find { it.address.hostAddress?.contains('.') == true }?.address?.hostAddress
        ipAddress = ip ?: "Not connected to WiFi"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview()
        Text(text = "http://$ipAddress:8080/video", modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun CameraPreview() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageFlow = remember { MutableStateFlow<ByteArray?>(null) }

    val server = remember {
        embeddedServer(Netty, port = 8080) {
            routing {
                get("/video") {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            while (isActive) {
                                val imageBytes = imageFlow.first { it != null }!!
                                channel.writeStringUtf8("--frame\r\n")
                                channel.writeStringUtf8("Content-Type: image/jpeg\r\n")
                                channel.writeStringUtf8("Content-Length: ${imageBytes.size}\r\n")
                                channel.writeStringUtf8("\r\n")
                                channel.writeFully(imageBytes)
                                channel.writeStringUtf8("\r\n")
                                channel.flush()
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
                val yBuffer = imageProxy.planes[0].buffer // Y
                val uBuffer = imageProxy.planes[1].buffer // U
                val vBuffer = imageProxy.planes[2].buffer // V

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                //U and V are swapped
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
