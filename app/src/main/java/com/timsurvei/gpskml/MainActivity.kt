package com.timsurvei.gpskml

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SurveyPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: String,
    val tagColor: String // "Blue" atau "Yellow"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GpsSurveiScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun GpsSurveiScreen() {
    val context = LocalContext.current
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var sessionName by remember { mutableStateOf("Sesi_Survei") }
    var isTracking by remember { mutableStateOf(false) }
    var lockLine by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf("Blue") }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var lastRecordedLocation by remember { mutableStateOf<Location?>(null) }
    val points = remember { mutableStateListOf<SurveyPoint>() }

    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasFineLocation = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasFineLocation) {
            isTracking = true
        } else {
            Toast.makeText(context, "Izin lokasi diperlukan untuk survei GPS", Toast.LENGTH_SHORT).show()
        }
    }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLocation = loc

                if (!isTracking) return

                val prev = lastRecordedLocation
                val distanceMeters = if (prev != null) loc.distanceTo(prev) else Float.MAX_VALUE

                // Lock Line: mencegah perekaman titik ganda/berulang saat diam atau jarak sangat dekat (< 2 meter)
                if (!lockLine || prev == null || distanceMeters >= 2.0f) {
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    points.add(
                        SurveyPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = if (loc.hasAltitude()) loc.altitude else 0.0,
                            timestamp = timeStr,
                            tagColor = selectedTag
                        )
                    )
                    lastRecordedLocation = loc
                }
            }
        }
    }

    DisposableEffect(isTracking, hasFineLocation) {
        if (isTracking && hasFineLocation) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } else {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "GPS Survei KML",
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = sessionName,
                onValueChange = { sessionName = it },
                label = { Text("Nama Sesi Survei") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Status: ${if (isTracking) "Merekam" else "Siap"} | Lock Line: ${if (lockLine) "AKTIF" else "NONAKTIF"}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val latText = currentLocation?.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "-"
                    val lonText = currentLocation?.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "-"
                    val accText = currentLocation?.accuracy?.let { String.format(Locale.US, "%.1f m", it) } ?: "-"
                    Text(text = "Lat: $latText | Lon: $lonText")
                    Text(text = "Akurasi: $accText | Total Titik: ${points.size}")
                }
            }

            Text(text = "Kategori / Penanda Titik:", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTag == "Blue",
                    onClick = { selectedTag = "Blue" },
                    label = { Text("Blue") }
                )
                FilterChip(
                    selected = selectedTag == "Yellow",
                    onClick = { selectedTag = "Yellow" },
                    label = { Text("Yellow") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isTracking) {
                            if (hasFineLocation) {
                                isTracking = true
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        } else {
                            isTracking = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isTracking) "Stop Survei" else "Mulai Survei")
                }

                OutlinedButton(
                    onClick = { lockLine = !lockLine },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (lockLine) "Buka Lock Line" else "Lock Line")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val safeName = sessionName.trim().ifEmpty { "Sesi_Survei" }
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(Date())
                        val fileName = "${safeName}_$fileStamp.kml"
                        val kmlContent = buildKml22(safeName, points)
                        val saved = saveKmlToDownloads(context, fileName, kmlContent)
                        if (saved) {
                            Toast.makeText(context, "Tersimpan di Download/$fileName", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Gagal menyimpan file KML", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = points.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ekspor KML")
                }

                OutlinedButton(
                    onClick = {
                        isTracking = false
                        points.clear()
                        lastRecordedLocation = null
                    },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Sesi")
                }
            }
        }
    }
}

private fun escapeXml(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun buildKml22(sessionTitle: String, points: List<SurveyPoint>): String {
    val safeTitle = escapeXml(sessionTitle)

    val placemarksXml = buildString {
        points.forEachIndexed { index, pt ->
            val styleUrl = if (pt.tagColor == "Yellow") "#yellowPin" else "#bluePin"
            append(
                """
                <Placemark>
                  <name>Titik ${index + 1} (${escapeXml(pt.tagColor)})</name>
                  <description>Waktu: ${escapeXml(pt.timestamp)} | Kategori: ${escapeXml(pt.tagColor)}</description>
                  <styleUrl>$styleUrl</styleUrl>
                  <Point>
                    <coordinates>${pt.longitude},${pt.latitude},${pt.altitude}</coordinates>
                  </Point>
                </Placemark>
                """.trimIndent()
            )
            append("\n")
        }
    }

    val lineCoords = points.joinToString(" ") { pt ->
        "${pt.longitude},${pt.latitude},${pt.altitude}"
    }

    val trackPlacemark = if (points.size >= 2) {
        """
        <Placemark>
          <name>Jalur $safeTitle</name>
          <styleUrl>#trackLine</styleUrl>
          <LineString>
            <tessellate>1</tessellate>
            <coordinates>$lineCoords</coordinates>
          </LineString>
        </Placemark>
        """.trimIndent()
    } else {
        ""
    }

    return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>$safeTitle</name>
    <Style id="bluePin">
      <IconStyle>
        <color>ffff0000</color>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/pushpin/blue-pushpin.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="yellowPin">
      <IconStyle>
        <color>ff00ffff</color>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/pushpin/ylw-pushpin.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="trackLine">
      <LineStyle>
        <color>ff00a5ff</color>
        <width>4</width>
      </LineStyle>
    </Style>
    $placemarksXml$trackPlacemark
  </Document>
</kml>
""".trimIndent()
}

private fun saveKmlToDownloads(context: Context, fileName: String, content: String): Boolean {
    return try {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.google-earth.kml+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
            }
            true
        }
    } catch (_: Exception) {
        false
    }
}
