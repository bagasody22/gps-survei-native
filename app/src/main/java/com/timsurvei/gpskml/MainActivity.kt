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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double
)

data class TaggedPoint(
    val name: String,
    val category: String,
    val note: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: String
)

private val BgDark = Color(0xFF0A0F1A)
private val CardDark = Color(0xFF121A2B)
private val TileDark = Color(0xFF0B1220)
private val BorderDark = Color(0xFF1F2A40)
private val TextWhite = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF94A3B8)
private val AccentBlue = Color(0xFF38BDF8)
private val BtnGreen = Color(0xFF10B981)
private val BtnGreenActive = Color(0xFF047857)
private val BtnAmber = Color(0xFFB45309)
private val BtnAmberLocked = Color(0xFFF59E0B)
private val BtnRed = Color(0xFF991B1B)
private val BtnRedPressed = Color(0xFFEF4444)
private val BtnTagCyan = Color(0xFF22D3EE)
private val BtnTagCyanPressed = Color(0xFF0891B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BgDark
            ) {
                GpsTaggingKmlScreen()
            }
        }
    }
}
@SuppressLint("MissingPermission")
@Composable
fun GpsTaggingKmlScreen() {
    val context = LocalContext.current
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var isTracking by remember { mutableStateOf(false) }
    var isLineLocked by remember { mutableStateOf(false) }
    var isRefreshingGps by remember { mutableStateOf(false) }
    var isSavingKml by remember { mutableStateOf(false) }
    var isTaggingPressed by remember { mutableStateOf(false) }
    var showSavedBadge by remember { mutableStateOf(false) }

    var kmlFileName by remember { mutableStateOf("") }
    var tagName by remember { mutableStateOf("") }
    var isTagNameAuto by remember { mutableStateOf(true) }
    val categories = listOf(
        "Tiang TE",
        "Tiang TB",
        "ODP",
        "UC/Closure"
    )
    var selectedCategory by remember { mutableStateOf(categories.first()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }

    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var lastTrackLocation by remember { mutableStateOf<Location?>(null) }
    var totalPathMeters by remember { mutableStateOf(0.0) }

    val trackPoints = remember { mutableStateListOf<TrackPoint>() }
    val taggedPoints = remember { mutableStateListOf<TaggedPoint>() }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(showSavedBadge) {
        if (showSavedBadge) {
            delay(3500L)
            showSavedBadge = false
        }
    }

    fun isAccurateEnough(loc: Location): Boolean {
        return !loc.hasAccuracy() || loc.accuracy <= 25.0f
    }

    fun appendTrackPointIfValid(loc: Location, minMeters: Double) {
        if (!isTracking || isLineLocked) return
        if (!isAccurateEnough(loc)) return
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return

        val alt = if (loc.hasAltitude()) loc.altitude else 0.0
        val prev = lastTrackLocation
        if (prev == null) {
            trackPoints.add(TrackPoint(loc.latitude, loc.longitude, alt))
            lastTrackLocation = loc
        } else {
            val dist = loc.distanceTo(prev).toDouble()
            if (dist in minMeters..150.0) {
                totalPathMeters += dist
                trackPoints.add(TrackPoint(loc.latitude, loc.longitude, alt))
                lastTrackLocation = loc
            }
        }
    }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLocation = loc
                isRefreshingGps = false
                if (isTracking && !isLineLocked) {
                    appendTrackPointIfValid(loc, minMeters = 3.0)
                }
            }
        }
    }

    DisposableEffect(hasLocationPermission, isTracking, isRefreshingGps) {
        val shouldListen = hasLocationPermission && (isTracking || isRefreshingGps)
        if (shouldListen) {
            val interval = if (isTracking) 1500L else 2000L
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    if (currentLocation == null || (loc.hasAccuracy() && loc.accuracy <= (currentLocation?.accuracy ?: Float.MAX_VALUE))) {
                        currentLocation = loc
                    }
                    if (isTracking && !isLineLocked) {
                        appendTrackPointIfValid(loc, minMeters = 3.0)
                    }
                }
            }
        } else {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            isTracking = true
            isLineLocked = false
            Toast.makeText(context, "Izin diberikan, survei aktif", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Izin lokasi diperlukan untuk survei GPS", Toast.LENGTH_SHORT).show()
        }
    }

    fun buildAutoTagName(category: String): String {
        val countInCategory = taggedPoints.count { it.category.equals(category, ignoreCase = true) } + 1
        return "$category $countInCategory"
    }

    fun selectCategory(newCategory: String) {
        selectedCategory = newCategory
        if (tagName.isBlank() || isTagNameAuto) {
            tagName = buildAutoTagName(newCategory)
            isTagNameAuto = true
        }
    }

    fun startSurvey() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        isTracking = true
        isLineLocked = false
        currentLocation?.let { appendTrackPointIfValid(it, minMeters = 0.0) }
        Toast.makeText(context, "Survei dimulai", Toast.LENGTH_SHORT).show()
    }

    fun refreshGpsPosition() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        isRefreshingGps = true
        Toast.makeText(context, "Mengambil posisi GPS terbaru...", Toast.LENGTH_SHORT).show()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    currentLocation = loc
                    if (isTracking && !isLineLocked) {
                        appendTrackPointIfValid(loc, minMeters = 2.5)
                    }
                    val accText = if (loc.hasAccuracy()) String.format(Locale.US, "±%.1f m", loc.accuracy) else "-"
                    Toast.makeText(context, "Posisi diperbarui (Akurasi $accText)", Toast.LENGTH_SHORT).show()
                }
                isRefreshingGps = false
            }
            .addOnFailureListener {
                isRefreshingGps = false
                Toast.makeText(context, "Gagal memperbarui posisi GPS", Toast.LENGTH_SHORT).show()
            }
    }

    fun finalizeTagWithLocation(targetLoc: Location) {
        val alt = if (targetLoc.hasAltitude()) targetLoc.altitude else 0.0
        val cleanName = tagName.trim().ifEmpty { buildAutoTagName(selectedCategory) }
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        taggedPoints.add(
            TaggedPoint(
                name = cleanName,
                category = selectedCategory,
                note = noteText.trim(),
                latitude = targetLoc.latitude,
                longitude = targetLoc.longitude,
                altitude = alt,
                timestamp = timeStr
            )
        )

        if (isTracking && !isLineLocked) {
            appendTrackPointIfValid(targetLoc, minMeters = 1.0)
        }

        tagName = buildAutoTagName(selectedCategory)
        isTagNameAuto = true
        noteText = ""
        isTaggingPressed = false
        Toast.makeText(context, "Titik '$cleanName' ditambahkan", Toast.LENGTH_SHORT).show()
    }

    fun addTagPoint() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        isTaggingPressed = true
        val screenLoc = currentLocation

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { freshLoc ->
                val chosenLoc: Location? = when {
                    freshLoc == null -> screenLoc
                    screenLoc == null -> freshLoc
                    else -> {
                        val jumpMeters = freshLoc.distanceTo(screenLoc)
                        val maxGlitchMeters = 12.0f
                        val freshWorseAccuracy = freshLoc.hasAccuracy() &&
                            screenLoc.hasAccuracy() &&
                            freshLoc.accuracy > screenLoc.accuracy + 5.0f

                        if (jumpMeters > maxGlitchMeters || freshWorseAccuracy) {
                            screenLoc
                        } else {
                            currentLocation = freshLoc
                            freshLoc
                        }
                    }
                }

                if (chosenLoc == null) {
                    isTaggingPressed = false
                    Toast.makeText(context, "Menunggu koordinat GPS...", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                if (chosenLoc.hasAccuracy() && chosenLoc.accuracy > 35.0f) {
                    Toast.makeText(
                        context,
                        String.format(Locale.US, "Peringatan: Akurasi ±%.1f m (kurang presisi)", chosenLoc.accuracy),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                finalizeTagWithLocation(chosenLoc)
            }
            .addOnFailureListener {
                if (screenLoc != null) {
                    finalizeTagWithLocation(screenLoc)
                } else {
                    isTaggingPressed = false
                    Toast.makeText(context, "Gagal mengambil posisi GPS", Toast.LENGTH_SHORT).show()
                }
            }
    }

    fun clearSessionData() {
        taggedPoints.clear()
        trackPoints.clear()
        lastTrackLocation = null
        totalPathMeters = 0.0
        isLineLocked = false
    }

    fun stopAndSaveKml() {
        isSavingKml = true
        isTracking = false
        isRefreshingGps = false
        fusedLocationClient.removeLocationUpdates(locationCallback)

        if (taggedPoints.isEmpty() && trackPoints.isEmpty()) {
            isSavingKml = false
            Toast.makeText(context, "Belum ada titik atau jalur untuk disimpan", Toast.LENGTH_SHORT).show()
            return
        }

        val rawBase = kmlFileName.trim()
            .removeSuffix(".kml")
            .removeSuffix(".KML")
            .ifEmpty {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                "survei_$stamp"
            }
        val safeBase = rawBase.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "survei_kml" }
        val fileName = "$safeBase.kml"

        val kmlContent = buildKml22(
            title = safeBase,
            taggedPoints = taggedPoints.toList(),
            trackPoints = trackPoints.toList()
        )

        val saved = saveKmlToDownloads(context, fileName, kmlContent)
        if (saved) {
            clearSessionData()
            showSavedBadge = true
            Toast.makeText(context, "Tersimpan di Download/$fileName", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Gagal menyimpan file KML", Toast.LENGTH_LONG).show()
        }
        isSavingKml = false
    }

    fun resetManual() {
        isTracking = false
        isRefreshingGps = false
        clearSessionData()
        Toast.makeText(context, "Riwayat sesi direset", Toast.LENGTH_SHORT).show()
    }
        Scaffold(
        containerColor = BgDark,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardDark)
                    .border(1.dp, BorderDark)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startSurvey() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTracking) BtnGreenActive else BtnGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isTracking) "▶ Berjalan" else "▶ Start",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { isLineLocked = !isLineLocked },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLineLocked) BtnAmberLocked else BtnAmber,
                            contentColor = if (isLineLocked) Color(0xFF111827) else Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isLineLocked) "Line Terkunci" else "Kunci Line",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { stopAndSaveKml() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSavingKml) BtnRedPressed else BtnRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isSavingKml) "Menyimpan..." else "■ Stop & Save",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.42f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isRefreshingGps) AccentBlue else Color(0xFF1E293B))
                            .border(
                                width = 1.dp,
                                color = if (isRefreshingGps) AccentBlue else BorderDark,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { refreshGpsPosition() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRefreshingGps) "⟳ Mencari..." else "⚙ GPS",
                            color = if (isRefreshingGps) Color(0xFF06202A) else TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = { addTagPoint() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTaggingPressed) BtnTagCyanPressed else BtnTagCyan,
                            contentColor = Color(0xFF06202A)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp),
                        modifier = Modifier.weight(0.58f)
                    ) {
                        Text(
                            text = "+ Tag Titik",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
                    Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isTracking) BtnGreen else AccentBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "GPS Tagging & KML",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TileDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when {
                            isLineLocked -> "LOCKED"
                            isTracking -> "TRACKING"
                            else -> "STANDBY"
                        },
                        color = if (isTracking) BtnGreen else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBox(
                        label = "TITIK TAG",
                        value = "${taggedPoints.size}",
                        valueColor = AccentBlue,
                        modifier = Modifier.weight(1f)
                    )
                    StatBox(
                        label = "PANJANG JALUR",
                        value = "${totalPathMeters.toInt()} m",
                        valueColor = AccentBlue,
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TileDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Koordinat GPS terkunci",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            text = when {
                                isRefreshingGps -> "• REFRESH"
                                currentLocation != null && isTracking -> "• AKTIF"
                                showSavedBadge -> "• TERSIMPAN"
                                currentLocation != null -> "• TERKUNCI"
                                else -> "• STANDBY"
                            },
                            color = if (currentLocation != null || showSavedBadge) BtnGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    val latStr = currentLocation?.latitude?.let {
                        String.format(Locale.US, "%.6f", it)
                    } ?: "-"
                    val lonStr = currentLocation?.longitude?.let {
                        String.format(Locale.US, "%.6f", it)
                    } ?: "-"
                    val altStr = currentLocation?.let { loc ->
                        if (loc.hasAltitude()) String.format(Locale.US, "%.1f m", loc.altitude) else "0.0 m"
                    } ?: "-"
                    val accStr = currentLocation?.accuracy?.let {
                        String.format(Locale.US, "±%.1f m", it)
                    } ?: "-"

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Latitude", color = TextMuted, fontSize = 11.sp)
                            Text(latStr, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Longitude", color = TextMuted, fontSize = 11.sp)
                            Text(lonStr, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Altitude", color = TextMuted, fontSize = 11.sp)
                            Text(altStr, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Akurasi", color = TextMuted, fontSize = 11.sp)
                            Text(accStr, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Nama File KML (Kosongkan = Otomatis)",
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = kmlFileName,
                        onValueChange = { kmlFileName = it },
                        placeholder = {
                            Text(
                                "Misal: survei_lapangan_01 (.kml otomatis)",
                                color = TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = TileDark,
                            unfocusedContainerColor = TileDark,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            cursorColor = AccentBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Nama Titik / Tag",
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = tagName,
                        onValueChange = {
                            tagName = it
                            isTagNameAuto = false
                        },
                        placeholder = {
                            Text(
                                "Contoh: Tiang TE 1 / ODP 1",
                                color = TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = TileDark,
                            unfocusedContainerColor = TileDark,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            cursorColor = AccentBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Kategori",
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TileDark)
                                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                    .clickable { categoryExpanded = true }
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedCategory,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("⌄", color = TextMuted, fontSize = 16.sp)
                            }

                            DropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false },
                                modifier = Modifier.background(CardDark)
                            ) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat, color = TextWhite, fontSize = 13.sp) },
                                        onClick = {
                                            selectCategory(cat)
                                            categoryExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Keterangan",
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            placeholder = {
                                Text(
                                    "Catatan singkat...",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = TileDark,
                                unfocusedContainerColor = TileDark,
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                cursorColor = AccentBlue
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Daftar Titik Sesi Ini",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF3B151A))
                            .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
                            .clickable { resetManual() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Reset Manual",
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }

                if (taggedPoints.isNotEmpty()) {
                    taggedPoints.reversed().forEachIndexed { revIdx, item ->
                        val nomorAsli = taggedPoints.size - revIdx
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TileDark)
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "$nomorAsli. ${item.name} (${item.category})",
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude),
                                color = AccentBlue,
                                fontSize = 12.sp
                            )
                            if (item.note.isNotEmpty()) {
                                Text(
                                    text = item.note,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun StatBox(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TileDark)
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
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

private fun categoryStyleId(category: String): String {
    return when (category.trim().lowercase(Locale.ROOT)) {
        "tiang te" -> "#styleTiangTe"
        "tiang tb" -> "#styleTiangTb"
        "odp" -> "#styleOdp"
        "uc/closure", "closure", "uc" -> "#styleUcClosure"
        else -> "#styleTiangTe"
    }
}

private fun buildKml22(
    title: String,
    taggedPoints: List<TaggedPoint>,
    trackPoints: List<TrackPoint>
): String {
    val safeTitle = escapeXml(title)

    val tagPlacemarks = buildString {
        taggedPoints.forEach { pt ->
            val descParts = buildList {
                add("Kategori: ${escapeXml(pt.category)}")
                add("Waktu: ${escapeXml(pt.timestamp)}")
                if (pt.note.isNotEmpty()) add("Keterangan: ${escapeXml(pt.note)}")
            }.joinToString(" | ")

            val styleUrl = categoryStyleId(pt.category)

            append(
                """
                <Placemark>
                  <name>${escapeXml(pt.name)}</name>
                  <description>$descParts</description>
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

    val lineCoords = trackPoints.joinToString(" ") { pt ->
        "${pt.longitude},${pt.latitude},${pt.altitude}"
    }

    val trackPlacemark = if (trackPoints.size >= 2) {
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
    <Style id="styleTiangTe">
      <IconStyle>
        <color>ff00ffff</color>
        <scale>1.1</scale>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/pushpin/ylw-pushpin.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="styleTiangTb">
      <IconStyle>
        <color>ffff0000</color>
        <scale>1.1</scale>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/pushpin/blue-pushpin.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="styleOdp">
      <IconStyle>
        <color>ff0000ff</color>
        <scale>1.15</scale>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="styleUcClosure">
      <IconStyle>
        <color>ff00ff00</color>
        <scale>1.15</scale>
        <Icon>
          <href>http://maps.google.com/mapfiles/kml/paddle/grn-diamond.png</href>
        </Icon>
      </IconStyle>
    </Style>
    <Style id="trackLine">
      <LineStyle>
        <color>ff0000ff</color>
        <width>4</width>
      </LineStyle>
    </Style>
    $tagPlacemarks$trackPlacemark
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
