package org.megatronik.megatronichud

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MegatronicHudApp()
        }
    }
}

@Composable
fun MegatronicHudApp() {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }

    // Контроллеры масштабирования камеры
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var zoomLevel by remember { mutableStateOf(1f) } // 1f=6x, 2f=12x, 4f=24x

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        permissionsGranted = perms.values.all { it }
    }

    LaunchedEffect(Unit) {
        launcher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionsGranted) {
            CameraPreviewWidget(onCameraControlReady = { cameraControl = it })
            
            LaunchedEffect(zoomLevel) {
                cameraControl?.setZoomRatio(zoomLevel)
            }

            TacticalHudOverlay(
                currentZoom = zoomLevel,
                onZoomChanged = { newZoom -> zoomLevel = newZoom }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("ТРЕБУЮТСЯ РАЗРЕШЕНИЯ НА КАМЕРУ И GPS...", color = Color.Green, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun CameraPreviewWidget(onCameraControlReady: (CameraControl) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProviderProvider = ProcessCameraProvider.getInstance(context)
        cameraProviderProvider.addListener({
            val cameraProvider = cameraProviderProvider.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
                onCameraControlReady(camera.cameraControl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
fun TacticalHudOverlay(currentZoom: Float, onZoomChanged: (Float) -> Unit) {
    val context = LocalContext.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    var compassHeading by remember { mutableStateOf(124.5f) }
    var horizonAngle by remember { mutableStateOf(0.0f) }
    var simHumidity by remember { mutableStateOf(64.15f) }
    val targetDist = 450
    var gpsLat by remember { mutableStateOf<Double?>(null) }
    var gpsLon by remember { mutableStateOf<Double?>(null) }

    // Анимационный стейт физической отдачи
    val recoilY = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    if (it.sensor.type == Sensor.TYPE_ORIENTATION) {
                        compassHeading = it.values[0]
                    }
                    if (it.sensor.type == Sensor.TYPE_GYROSCOPE) {
                        val rz = it.values[2]
                        horizonAngle = (horizonAngle + Math.toDegrees(rz.toDouble() * 0.033).toFloat()) % 360f
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 1f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        gpsLat = location.latitude
                        gpsLon = location.longitude
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (e: SecurityException) { e.printStackTrace() }

        while (true) {
            simHumidity += Random.nextFloat() * 0.02f - 0.01f
            if (rotSensor == null) {
                horizonAngle = (horizonAngle + Random.nextFloat() * 1.0f - 0.5f) % 360f
            }
            if (magSensor == null) {
                compassHeading = (compassHeading + Random.nextFloat() * 0.7f - 0.3f) % 360f
            }
            delay(33)
        }
    }

    val airFactor = 1.0f - (simHumidity / 1500.0f)
    val bulletDrop = (9.81f * (targetDist / 600.0f).pow(2) * 40f) * airFactor

    val zoomText = when(currentZoom) {
        2f -> "12x  50"
        4f -> "24x  50"
        else -> "6x  50"
    }

    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val telemetryText = """
            HUD: MEGATRONIC PC v5.0
            OPERATOR: LEXAN CORE
            GEOMETRY: HUD FULL ONLINE
            ---------------------------------
            ELEVATION: ${String.format(Locale.US, "%.2f", bulletDrop)} MOA
            AZIMUTH: ${String.format(Locale.US, "%.1f", compassHeading)}°
            BARO HUMID: ${String.format(Locale.US, "%.2f", simHumidity)}%
            RANGEFINDER: ${targetDist}M
        """.trimIndent()

        Text(
            text = telemetryText,
            color = Color(0xFF00FF00),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).background(Color(0x33000000)).padding(8.dp)
        )

        val coordText = "${formatCoord(gpsLat, true)} - ${formatCoord(gpsLon, false)}"
        Text(
            text = coordText,
            color = Color(0xDD00FF00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        )

        Text(
            text = zoomText,
            color = Color(0xEE00FF00),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 20.dp)
        )

        // Блок интерфейса тактических кнопок (Справа по центру)
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Кнопка Переключения Зума
            Box(
