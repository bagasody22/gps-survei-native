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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

enum class MarkerIconOption(val label: String, val kmlColor: String, val iconUrl: String) {
    BLUE("Penanda Biru", "ffff0000", "http://maps.google.com/mapfiles/kml/paddle/blu-circle.png"),
    YELLOW("Penanda Kuning", "ff00ffff", "http://maps.google.com/mapfiles/kml/paddle/ylw-circle.png")
}

data class GpsCoord(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val acc: Float
)

data class SurveyPoint(
    val id: Long,
    val name: String,
    val category: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val acc: Float,
    val time: String
)

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun calcTotalDistance(track: List<GpsCoord>): Double {
    var total = 0.0
    for (i in 1 until track.size) {
        total += haversineMeters(track[i - 1].lat, track[i - 1].lon, track[i].lat, track[i].lon)
    }
    return total
}

private fun escapeXml(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun buildLineOnlyKml(
    docTitle: String,
    iconOption: MarkerIconOption,
    points: List<SurveyPoint>,
    trackCoords: List<GpsCoord>
): String {
    val orderedPoints = points.reversed()
    val pointPlacemarks = buildString {
        for (p in orderedPoints) {
            val desc = "Kategori: ${escapeXml(p.category)} \vert{} Catatan:${escapeXml(p.note.ifBlank { "-" })} | Akurasi: ±${String.format(Locale.US, "\%.1f", p.acc)}m \vert{} Waktu: ${escapeXml(p.time)}"
            val coord = String.format(Locale.US, "%.6f,%.6f,%.1f", p.lon, p.lat, p.alt)
            append("\n  <Placemark><name>${escapeXml(p.name)}</name><description>$desc</description><styleUrl>#pointStyle</styleUrl><Point><altitudeMode>clampToGround</altitudeMode><coordinates>$coord</coordinates></Point></Placemark>")
        }
    }

    val lineSource = if (trackCoords.size >= 2) {
        trackCoords
    } else {
        orderedPoints.map { GpsCoord(it.lat, it.lon, it.alt, it.acc) }
    }

    val trackPlacemark = if (lineSource.size >= 2) {
        val lineStr = lineSource.joinToString(" ") {
            String.format(Locale.US, "%.6f,%.6f,%.1f", it.lon, it.lat, it.alt)
        }
        "\n  <Placemark><name>Jalur - ${escapeXml(docTitle)}</name><styleUrl>#lineStyle</styleUrl><LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>$lineStr</coordinates></LineString></Placemark>"
    } else {
        ""
    }

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <Document>
          <name>${escapeXml(docTitle)}</name>
          <Style id="pointStyle"><IconStyle><color>${iconOption.kmlColor}</color><scale>1.1</scale><Icon><href>${iconOption.iconUrl}</href></Icon></IconStyle></Style>
          <Style id="lineStyle"><LineStyle><color>ff0000ff</color><width>4</width></LineStyle></Style>$pointPlacemarks$trackPlacemark
        </Document>
        </kml>
    """.trimIndent()
}

private fun saveKmlToDownloads(context: Context, fileName: String, kmlContent: String): String {
    val bytes = kmlContent.toByteArray(Charsets.UTF_8)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.google-earth.kml+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Gagal membuat file di Downloads")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Gagal menulis isi KML")
    } else {
        @Suppress("DEPRECATION")
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        File(dlDir, fileName).writeBytes(bytes)
    }
    return "Download/$fileName"
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GpsSurveiNativeApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsSurveiNativeApp() {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var hasLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var currentPos by remember { mutableStateOf<GpsCoord?>(null) }
    var gpsStatus by remember { mutableStateOf("Menunggu izin / sinyal GPS...") }
    var isRecording by remember { mutableStateOf(false) }
    var isTrackLocked by remember { mutableStateOf(false) }

    var fileNameInput by remember { mutableStateOf("") }
    var pointNameInput by remember { mutableStateOf("") }
    var pointNoteInput by remember { mutableStateOf("") }
    val categories = listOf("Titik Utama", "Tiang / Jalur", "Simpang", "Catatan")
    var selectedCategory by remember { mutableStateOf(categories.first()) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var selectedIcon by remember { mutableStateOf(MarkerIconOption.BLUE) }

    var points by remember { mutableStateOf(listOf<SurveyPoint>()) }
    var trackCoords by remember { mutableStateOf(listOf<GpsCoord>()) }
    var statusBanner by remember { mutableStateOf("Tekan Start untuk mulai sesi baru.") }

    fun onLocationUpdate(loc: Location) {
        val coord = GpsCoord(
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        )
        currentPos = coord
        gpsStatus = "● GPS AKTIF"

        if (isRecording && !isTrackLocked) {
            val last = trackCoords.lastOrNull()
            if (last == null || haversineMeters(last.lat, last.lon, coord.lat, coord.lon) >= 2.0) {
                trackCoords = trackCoords + coord
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPerm = granted
        gpsStatus = if (granted) "Mencari sinyal GPS..." else "Izin lokasi ditolak"
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPerm) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(hasLocationPerm) {
        if (!hasLocationPerm) {
            onDispose {}
        } else {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onLocationUpdate(it) }
                }
            }
            try {
                fusedClient.requestLocationUpdates(req, callback, Looper.getMainLooper())
            } catch (_: SecurityException) {
                gpsStatus = "Izin lokasi belum aktif"
            }
            onDispose {
                fusedClient.removeLocationUpdates(callback)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshGps() {
        if (!hasLocationPerm) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        gpsStatus = "Memperbarui GPS..."
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) onLocationUpdate(loc)
                else gpsStatus = "Sinyal belum ditemukan"
            }
            .addOnFailureListener {
                gpsStatus = "Gagal ambil GPS"
            }
    }

    fun resetSessionAuto() {
        isRecording = false
        isTrackLocked = false
        points = emptyList()
        trackCoords = emptyList()
        pointNameInput = ""
        pointNoteInput = ""
        fileNameInput = ""
    }

    fun startSession() {
        val pos = currentPos
        if (pos == null) {
            Toast.makeText(context, "Tunggu koordinat GPS muncul dulu sebelum Start.", Toast.LENGTH_SHORT).show()
            return
        }
        points = emptyList()
        trackCoords = listOf(pos)
        isRecording = true
        isTrackLocked = false
        statusBanner = "▶ Sesi berjalan: jalur merah & tag aktif."
    }

    fun toggleLockLine() {
        if (!isRecording) return
        isTrackLocked = !isTrackLocked
        statusBanner = if (isTrackLocked) {
            "🔒 Line dikunci: jalur balik tidak digandakan."
        } else {
            "🔓 Line dibuka: perekaman jalur aktif kembali."
        }
    }

    fun addTagPoint() {
        val pos = currentPos
        if (pos == null) {
            Toast.makeText(context, "Tunggu sampai koordinat GPS muncul dulu ya.", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanName = pointNameInput.trim().ifEmpty { "Titik ${points.size + 1}" }
        val nowStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val item = SurveyPoint(
            id = System.currentTimeMillis(),
            name = cleanName,
            category = selectedCategory,
            note = pointNoteInput.trim(),
            lat = pos.lat,
            lon = pos.lon,
            alt = pos.alt,
            acc = pos.acc,
            time = nowStr
        )
        points = listOf(item) + points

        if (isRecording && !isTrackLocked) {
            val last = trackCoords.lastOrNull()
            if (last == null || haversineMeters(last.lat, last.lon, pos.lat, pos.lon) > 0.5) {
                trackCoords = trackCoords + pos
            }
        }
        pointNameInput = ""
        pointNoteInput = ""
        Toast.makeText(context, "Ditambahkan: $cleanName", Toast.LENGTH_SHORT).show()
    }

    fun stopAndSave() {
        val pos = currentPos
        val finalTrack = if (pos != null && isRecording && !isTrackLocked && trackCoords.isNotEmpty()) {
            val last = trackCoords.last()
            if (haversineMeters(last.lat, last.lon, pos.lat, pos.lon) > 0.5) trackCoords + pos else trackCoords
        } else {
            trackCoords
        }

        if (points.isEmpty() && finalTrack.size < 2) {
            Toast.makeText(context, "Belum ada titik atau jalur untuk disimpan.", Toast.LENGTH_SHORT).show()
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cleanBase = fileNameInput.trim()
            .replace(Regex("\\.kml$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9_-]+"), "_")
            .trim('_')
        val title = cleanBase.ifEmpty { "survei_gps_$stamp" }
        val fileName = "$title.kml"

        try {
            val kml = buildLineOnlyKml(title, selectedIcon, points, finalTrack)
            val savedPath = saveKmlToDownloads(context, fileName, kml)
            resetSessionAuto()
            statusBanner = "✅ Tersimpan di $savedPath • Sesi otomatis di-reset."
            Toast.makeText(context, "Tersimpan di $savedPath", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            statusBanner = "⚠️ Gagal simpan: ${e.message}"
            Toast.makeText(context, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val distanceMeters = calcTotalDistance(trackCoords)
    val distanceText = if (distanceMeters >= 1000.0) {
        String.format(Locale.US, "%.2f km", distanceMeters / 1000.0)
    } else {
        "${distanceMeters.roundToInt()} m"
    }

    Scaffold(
        containerColor = Color(0xFF0B1120),
        topBar = {
            Surface(color = Color(0xFF0F172A), tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "GPS Survei KML",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (isRecording) Color(0x33EF4444) else Color(0x2694A3B8))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isRecording) "● REC" else "STANDBY",
                                color = if (isRecording) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isTrackLocked) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Color(0x33F59E0B))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("LOCK", color = Color(0xFFFCD34D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0F172A),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { startSession() },
                            enabled = !isRecording,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color(0xFF052E16)
                            )
                        ) {
                            Text("▶ Start", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        Button(
                            onClick = { toggleLockLine() },
                            enabled = isRecording,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTrackLocked) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                                contentColor = Color(0xFF111827)
                            )
                        ) {
                            Text(
                                text = if (isTrackLocked) "🔓 Buka" else "🔒 Kunci",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = { stopAndSave() },
                            enabled = isRecording,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White
                            )
                        ) {
                            Text("⏹ Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { refreshGps() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🔄 GPS", color = Color(0xFFFCD34D), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { addTagPoint() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0284C7),
                                contentColor = Color.White
                            )
                        ) {
                            Text("➕ Tambah Titik", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
              LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF17233A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetricBox("Titik Ditandai", "${points.size}", Modifier.weight(1f))
                            MetricBox("Panjang Line", distanceText, Modifier.weight(1f))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentPos != null) "Koordinat GPS terkunci" else "Mencari koordinat GPS...",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                            Text(
                                text = gpsStatus,
                                color = if (currentPos != null) Color(0xFF34D399) else Color(0xFFFCD34D),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF09101F))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                CoordItem("Latitude", currentPos?.let { String.format(Locale.US, "%.6f", it.lat) } ?: "-", Modifier.weight(1f))
                                CoordItem("Longitude", currentPos?.let { String.format(Locale.US, "%.6f", it.lon) } ?: "-", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth()) {
                                CoordItem("Altitude", currentPos?.let { String.format(Locale.US, "%.1f m", it.alt) } ?: "-", Modifier.weight(1f))
                                CoordItem("Akurasi", currentPos?.let { String.format(Locale.US, "±%.1f m", it.acc) } ?: "-", Modifier.weight(1f))
                            }
                        }

                        OutlinedTextField(
                            value = fileNameInput,
                            onValueChange = { fileNameInput = it },
                            label = { Text("Nama Berkas KML (bisa diedit)") },
                            placeholder = { Text("contoh: jalur_kebun_01") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Pilihan Warna Ikon Tagging (Google Earth)",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MarkerIconOption.entries.forEach { option ->
                                val active = selectedIcon == option
                                val chipColor = if (option == MarkerIconOption.BLUE) Color(0xFF38BDF8) else Color(0xFFFACC15)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(if (active) Color(0x2638BDF8) else Color(0xFF09101F))
                                        .border(
                                            width = if (active) 1.5.dp else 1.dp,
                                            color = if (active) chipColor else Color(0x3394A3B8),
                                            shape = RoundedCornerShape(11.dp)
                                        )
                                        .clickable { selectedIcon = option }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(chipColor)
                                    )
                                    Text(
                                        text = if (option == MarkerIconOption.BLUE) "Biru" else "Kuning",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = pointNameInput,
                            onValueChange = { pointNameInput = it },
                            label = { Text("Nama Titik") },
                            placeholder = { Text("contoh: Patok 01 / Tiang A") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Box {
                            OutlinedTextField(
                                value = selectedCategory,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Kategori") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { categoryMenuOpen = true }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { categoryMenuOpen = true }
                            )
                            DropdownMenu(
                                expanded = categoryMenuOpen,
                                onDismissRequest = { categoryMenuOpen = false }
                            ) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat) },
                                        onClick = {
                                            selectedCategory = cat
                                            categoryMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = pointNoteInput,
                            onValueChange = { pointNoteInput = it },
                            label = { Text("Keterangan") },
                            placeholder = { Text("Catatan singkat...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = statusBanner,
                            color = Color(0xFF34D399),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Daftar Titik Sesi Ini (${points.size})",
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            items(points, key = { it.id }) { p ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF17233A)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(text = p.category, color = Color(0xFF7DD3FC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = p.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = String.format(Locale.US, "%.6f, %.6f • Alt %.1f m (±%.1f m)", p.lat, p.lon, p.alt, p.acc),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        if (p.note.isNotBlank()) {
                            Text(text = "📝 ${p.note}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        }
                        Text(text = "🕒 ${p.time}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF09101F))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label.uppercase(), color = Color(0xFF94A3B8), fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(text = value, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun CoordItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
