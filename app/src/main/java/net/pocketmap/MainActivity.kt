package net.pocketmap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.BoundingBox
import android.widget.PopupMenu
import java.util.concurrent.Executors
import java.io.File

class MainActivity : Activity(), LocationListener {
    private lateinit var basemaps: List<Basemap>
    private lateinit var selectedBasemap: Basemap
    private lateinit var basemapButton: Button
    private lateinit var attribution: TextView
    private lateinit var zoomLevel: TextView
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var recenter: android.widget.ImageButton
    private lateinit var manager: LocationManager
    private val trailExecutor = Executors.newSingleThreadExecutor()
    private lateinit var trailStatus: TextView
    private lateinit var trailRepository: TrailRepository
    private val trailOverlay = FolderOverlay()
    private var trailDocument: TrailKml.Document? = null
    private var refreshingTrails = false
    private var destroyed = false
    private var trailsVisible = true
    private lateinit var recordButton: android.widget.ImageButton
    private lateinit var recordStatus: TextView
    private val recordingOverlay = FolderOverlay()
    private var lastRecordedPoint: RecordedTrack.Point? = null
    // Display-only connector: never append foreground fixes to the saved track.
    private val liveRecordingOverlay = object : Overlay() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(21, 101, 192)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || !RecordingService.running) return
            val last = lastRecordedPoint ?: return
            val fix = current ?: return
            if (prefs.getString("recorded_track", null) != displayedTrack) return
            // Follow existing gap handling and do not connect stale/backward fixes.
            if (fix.time < last.time || fix.time - last.time > 90_000L ||
                android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > 30_000_000_000L) return
            val from = mapView.projection.toPixels(GeoPoint(last.latitude, last.longitude), null)
            val to = mapView.projection.toPixels(GeoPoint(fix.latitude, fix.longitude), null)
            paint.strokeWidth = dp(4).toFloat()
            canvas.drawLine(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), paint)
        }
    }
    private val recordingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var displayedTrack = ""
    private var displayedLength = -1L
    private var recordedPointCount = 0
    private val recordingTick = object : Runnable {
        override fun run() { updateRecording(); if (facingHeading != null) map.invalidate(); if (active) recordingHandler.postDelayed(this, 1000) }
    }
    private var following = true
    private var active = false
    private var current: Location? = null
    private lateinit var phoneHeading: PhoneHeading
    private var facingHeading: Float? = null
    private var headingTime = 0L
    private var reportText: String? = null
    private var reportAccuracy = 0
    private var reportPhoto: String? = null
    private var cameraPhoto: String? = null
    private var reportEditor: android.widget.EditText? = null
    private var reportDialog: android.app.AlertDialog? = null
    private val prefs by lazy { getSharedPreferences("map", MODE_PRIVATE) }
    private val dot = object : Overlay() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val fix = current ?: return
            val point = mapView.projection.toPixels(GeoPoint(fix.latitude, fix.longitude), null)
            val heading = facingHeading?.takeIf { android.os.SystemClock.elapsedRealtime() - headingTime < 5_000L }
            paint.style = Paint.Style.FILL
            if (heading == null) {
                paint.color = Color.WHITE
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(10).toFloat(), paint)
                paint.color = Color.rgb(21, 101, 192)
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(7).toFloat(), paint)
            } else {
                val saved = canvas.save()
                canvas.translate(point.x.toFloat(), point.y.toFloat())
                canvas.rotate(heading)
                val triangle = android.graphics.Path().apply {
                    moveTo(0f, -dp(15).toFloat())
                    lineTo(dp(10).toFloat(), dp(10).toFloat())
                    lineTo(-dp(10).toFloat(), dp(10).toFloat())
                    close()
                }
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(4).toFloat()
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(triangle, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(21, 101, 192)
                canvas.drawPath(triangle, paint)
                canvas.restoreToCount(saved)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "Trail/1.12 (net.pocketmap; personal Android map)"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "tiles")
        }
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.WHITE)
        }
        val statusColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(statusColumn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        status = TextView(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); text = "Waiting for location…" }
        statusColumn.addView(status)
        trailStatus = TextView(this).apply { setPadding(dp(12), 0, dp(12), dp(6)); text = "Loading trails…" }
        statusColumn.addView(trailStatus)
        recordStatus = TextView(this).apply {
            setPadding(dp(12), 0, dp(12), dp(4))
            visibility = android.view.View.GONE
        }
        statusColumn.addView(recordStatus)
        basemaps = Basemap.load(assets)
        selectedBasemap = basemaps.find { it.name == prefs.getString("basemap", "openstreetmap") }
            ?: basemaps.find { it.name == "openstreetmap" } ?: basemaps.first()
        map = MapView(this, tileProvider(selectedBasemap)).apply {
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(prefs.getFloat("zoom", 17f).toDouble().coerceAtMost(19.0))
            controller.setCenter(GeoPoint(prefs.getFloat("lat", 40.7f).toDouble(), prefs.getFloat("lon", -74.7f).toDouble()))
            overlays.add(trailOverlay)
            overlays.add(recordingOverlay)
            overlays.add(liveRecordingOverlay)
            overlays.add(dot)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { showAttribution(); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { showAttribution(); return false }
            })
        }
        var startX = 0f
        var startY = 0f
        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && (kotlin.math.abs(event.x - startX) > dp(12) || kotlin.math.abs(event.y - startY) > dp(12))) {
                    following = false
                    refreshStatus()
                }
            }
            false
        }
        val mapFrame = android.widget.FrameLayout(this)
        mapFrame.addView(map, android.widget.FrameLayout.LayoutParams(-1, -1))
        basemapButton = Button(this).apply {
            text = selectedBasemap.name + " ▾"
            contentDescription = "Choose basemap: ${selectedBasemap.name}"
            elevation = dp(6).toFloat()
            setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    basemaps.forEachIndexed { index, basemap ->
                        menu.add(0, index, index, basemap.name).apply {
                            isCheckable = true
                            isChecked = basemap.name == selectedBasemap.name
                        }
                    }
                    setOnMenuItemClickListener { item ->
                        selectedBasemap = basemaps[item.itemId]
                        map.setTileProvider(tileProvider(selectedBasemap))
                        map.maxZoomLevel = 19.0
                        prefs.edit().putString("basemap", selectedBasemap.name).apply()
                        basemapButton.text = selectedBasemap.name + " ▾"
                        basemapButton.contentDescription = "Choose basemap: ${selectedBasemap.name}"
                        showAttribution()
                        map.invalidate()
                        true
                    }
                    show()
                }
            }
        }
        mapFrame.addView(basemapButton, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        recordButton = android.widget.ImageButton(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setOnClickListener { toggleRecording() }
        }
        mapFrame.addView(recordButton, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        val flagButton = android.widget.ImageButton(this).apply {
            contentDescription = "Share current location"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_report_flag)
            setOnClickListener { shareCurrentLocation() }
        }
        mapFrame.addView(flagButton, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        root.addView(mapFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        recenter = android.widget.ImageButton(this).apply {
            contentDescription = "Recenter and follow GPS"
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_recenter)
            setOnClickListener {
                following = true
                if (!hasPermission()) requestLocationPermission()
                else if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                current?.let { map.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
                refreshStatus()
            }
        }
        mapFrame.addView(recenter, android.widget.FrameLayout.LayoutParams(dp(37), dp(37), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        val headerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.END
        }
        val menuButton = Button(this).apply {
            text = "☰"
            textSize = 24f
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
            contentDescription = "Menu"
            setOnClickListener { button ->
                PopupMenu(this@MainActivity, button).apply {
                    menu.add(0, 1, 0, "Refresh trails")
                    menu.add(0, 2, 1, "Zoom to trails")
                    menu.add(0, 3, 2, if (trailsVisible) "Hide trails" else "Show trails")
                    menu.add(0, 6, 3, "Legend")
                    menu.add(0, 4, 4, "Share recorded KML…")
                    menu.add(0, 5, 5, "Share recorded GPX…")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            4 -> chooseRecordedTrack(false)
                            5 -> chooseRecordedTrack(true)
                            1 -> refreshTrails()
                            2 -> trailDocument?.let { document ->
                                val points = document.features.flatMap { it.points }.map { GeoPoint(it.latitude, it.longitude) }
                                following = false
                                trailsVisible = true
                                trailOverlay.isEnabled = true
                                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, dp(40))
                                refreshStatus()
                            }
                            3 -> { trailsVisible = !trailsVisible; trailOverlay.isEnabled = trailsVisible; map.invalidate() }
                            6 -> showLegend()
                        }
                        true
                    }
                    show()
                }
            }
        }
        attribution = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            includeFontPadding = false
            setTextColor(Color.DKGRAY)
            text = "ⓘ"
            setOnClickListener {
                val credits = TextView(this@MainActivity).apply {
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    text = android.text.Html.fromHtml(
                        selectedBasemap.attribution + (selectedBasemap.attributionUrl?.let { "<br><br><a href=\"$it\">Source and licence details</a>" } ?: "") +
                        if (selectedBasemap.name == "opentopomap") "<br><br><a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap · ODbL</a><br><a href=\"https://creativecommons.org/licenses/by-sa/3.0/\">Map style · CC BY-SA 3.0</a>" else "",
                        android.text.Html.FROM_HTML_MODE_LEGACY)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Map credits").setView(credits).setPositiveButton("Done", null).show()
            }
        }

        zoomLevel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            setTextColor(Color.DKGRAY)
        }
        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            contentDescription = "Map attribution, licences, and zoom level"
            setOnClickListener { attribution.performClick() }
        }
        infoColumn.addView(attribution, LinearLayout.LayoutParams(dp(48), dp(24)))
        infoColumn.addView(zoomLevel, LinearLayout.LayoutParams(dp(48), dp(24)))
        headerActions.addView(infoColumn, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerActions.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(headerActions, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END })
        showAttribution()
        setContentView(root)
        @Suppress("DEPRECATION")
        val rotation = { windowManager.defaultDisplay.rotation }
        phoneHeading = PhoneHeading(this, rotation, { current }) { heading ->
            facingHeading = heading
            headingTime = android.os.SystemClock.elapsedRealtime()
            map.invalidate()
        }
        trailRepository = TrailRepository(filesDir)
        refreshTrails()
        if (!hasPermission()) requestLocationPermission()
        savedInstanceState?.let {
            reportText = it.getString("reportText")
            reportAccuracy = it.getInt("reportAccuracy")
            reportPhoto = it.getString("reportPhoto")
            cameraPhoto = it.getString("cameraPhoto")
        }
        if (reportText != null && cameraPhoto == null) showReport()
    }

    private fun showLegend() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val rows = listOf(
            Color.rgb(219, 68, 54) to "Blocked",
            Color.rgb(248, 151, 27) to "Unknown",
            Color.rgb(244, 235, 55) to "TTA trail",
            Color.rgb(0, 157, 87) to "Public trail"
        )
        for ((color, label) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(android.view.View(this).apply {
                setBackgroundColor(color)
            }, LinearLayout.LayoutParams(dp(48), dp(5)))
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
            }, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(12)
            })
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Legend")
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun tileProvider(basemap: Basemap): TrailTileProvider {
        val stores = BasemapTileStorage.prepare(this, basemap)
        return TrailTileProvider.create(this, basemap.tileSource(), stores.seedFile, stores.cacheFile)
    }

    private fun showAttribution() {
        attribution.text = "ⓘ"
        zoomLevel.text = formatZoom(map.zoomLevelDouble)
    }

    private fun formatZoom(zoom: Double): String = if (zoom % 1.0 == 0.0) zoom.toInt().toString() else String.format(java.util.Locale.US, "%.1f", zoom)

    private fun shareCurrentLocation() {
        val fix = current?.let { Location(it) }
        if (fix == null || android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > 30_000_000_000L) {
            android.widget.Toast.makeText(this, "Waiting for a current location — try again in a moment", android.widget.Toast.LENGTH_LONG).show()
            if (!hasPermission()) requestLocationPermission() else startLocation()
            return
        }
        if (reportText == null) {
            reportText = LocationReport.message(fix.latitude, fix.longitude)
            reportAccuracy = fix.accuracy.toInt()
        }
        showReport()
    }

    private fun photoFile(name: String): File = ReportPhotoProvider.photoFile(filesDir, name)
    private fun photoUri(name: String) = android.net.Uri.Builder().scheme("content")
        .authority("$packageName.photos").appendPath("photo").appendPath(name).build()

    private fun showReport() {
        if (reportDialog?.isShowing == true || reportText == null) return
        val message = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setText(reportText)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        reportEditor = message
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Share current location")
            .setMessage("GPS accuracy ±$reportAccuracy m. Add a description if you like." +
                if (reportPhoto != null) "\nPhoto attached." else "")
            .setView(message)
            .setPositiveButton("Share", null)
            .setNeutralButton(if (reportPhoto == null) "Take photo" else "Retake photo", null)
            .setNegativeButton("Cancel") { _, _ -> clearReport() }
            .setOnCancelListener { clearReport() }
            .create()
        reportDialog = dialog
        dialog.setOnDismissListener { reportEditor = null; reportDialog = null }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            reportText = message.text.toString()
            takeReportPhoto()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            reportText = message.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (reportPhoto == null) "text/plain" else "image/jpeg"
                putExtra(Intent.EXTRA_TEXT, reportText!!.trim())
                putExtra(Intent.EXTRA_EMAIL, arrayOf("mbligh@mbligh.org"))
                putExtra(Intent.EXTRA_SUBJECT, "Trail location report")
                reportPhoto?.let {
                    val uri = photoUri(it)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("Trail report photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(intent, "Share location report"))
                // Keep the attachment available while the receiving app reads it.
                clearReport(deletePhoto = false)
                dialog.dismiss()
            } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No app available to share this report", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun takeReportPhoto() {
        var name: String? = null
        try {
            val directory = File(filesDir, "report-photos").apply { mkdirs() }
            name = File.createTempFile("Trail-report-", ".jpg", directory).name
            val uri = photoUri(name)
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("Trail report photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            cameraPhoto = name
            startActivityForResult(intent, 3)
            reportDialog?.dismiss()
        } catch (_: Exception) {
            name?.let { photoFile(it).delete() }
            cameraPhoto = null
            android.widget.Toast.makeText(this, "Unable to open the camera app", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform camera activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 3) return
        val name = cameraPhoto ?: return
        cameraPhoto = null
        revokeUriPermission(photoUri(name), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val file = photoFile(name)
        if (resultCode == RESULT_OK && file.length() > 0) {
            reportPhoto?.let { photoFile(it).delete() }
            reportPhoto = name
        } else {
            file.delete()
            if (resultCode == RESULT_OK) android.widget.Toast.makeText(this, "The camera did not save a photo. You can try again.", android.widget.Toast.LENGTH_LONG).show()
        }
        showReport()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        reportEditor?.let { reportText = it.text.toString() }
        outState.putString("reportText", reportText)
        outState.putInt("reportAccuracy", reportAccuracy)
        outState.putString("reportPhoto", reportPhoto)
        outState.putString("cameraPhoto", cameraPhoto)
        super.onSaveInstanceState(outState)
    }

    private fun clearReport(deletePhoto: Boolean = true) {
        if (deletePhoto) reportPhoto?.let { photoFile(it).delete() }
        reportText = null
        reportPhoto = null
        reportEditor = null
    }

    private fun toggleRecording() {
        if (RecordingService.running) {
            lastRecordedPoint = null
            map.invalidate()
            stopService(Intent(this, RecordingService::class.java))
            android.app.AlertDialog.Builder(this).setTitle("Recording saved")
                .setMessage("Your track is saved on this phone.")
                .setPositiveButton("Share KML") { _, _ -> latestRecording()?.let { shareRecording(it, false) } }
                .setNegativeButton("Done", null).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission()
            android.widget.Toast.makeText(this, "Grant precise location, then tap Record again", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return
        }
        startForegroundService(Intent(this, RecordingService::class.java))
    }
    private fun recordings() = File(filesDir, "recordings").listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.name } ?: emptyList()
    private fun latestRecording(): File? = prefs.getString("recorded_track", null)?.let { File(File(filesDir, "recordings"), it) }?.takeIf { it.exists() }
    private fun chooseRecordedTrack(gpx: Boolean) {
        val files = recordings()
        if (files.isEmpty()) { android.widget.Toast.makeText(this, "No recordings yet", android.widget.Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this).setTitle("Share recorded track")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, index -> shareRecording(files[index], gpx) }.show()
    }
    private fun shareRecording(file: File, gpx: Boolean = false) {
        val points = RecordedTrack.read(file)
        if (points.isEmpty()) { android.widget.Toast.makeText(this, "No GPS points recorded yet", android.widget.Toast.LENGTH_LONG).show(); return }
        try {
            val directory = File(filesDir, "exports").apply { mkdirs() }
            val extension = if (gpx) "gpx" else "kml"
            val export = File(directory, file.nameWithoutExtension + "." + extension)
            export.writeText(if (gpx) RecordedTrack.gpx(file.nameWithoutExtension, points) else RecordedTrack.kml(file.nameWithoutExtension, points))
            val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.files").appendPath("export").appendPath(export.name).build()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (gpx) "application/gpx+xml" else "application/vnd.google-earth.kml+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                clipData = android.content.ClipData.newRawUri("Recorded trail", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (gpx) "Share GPX track" else "Share KML track"))
        } catch (_: Exception) { android.widget.Toast.makeText(this, "Could not share this track", android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun updateRecording() {
        val running = RecordingService.running
        recordButton.contentDescription = if (running) "Stop recording" else "Start recording"
        recordButton.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
            shape = if (running) android.graphics.drawable.GradientDrawable.RECTANGLE else android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.RED); setSize(dp(24), dp(24)); if (running) cornerRadius = dp(3).toFloat()
        })
        val file = latestRecording()
        if (file != null && (file.name != displayedTrack || file.length() != displayedLength)) {
            displayedTrack = file.name; displayedLength = file.length()
            val points = RecordedTrack.read(file)
            recordedPointCount = points.size
            lastRecordedPoint = points.lastOrNull()
            recordingOverlay.items.clear()
            val segments = mutableListOf<MutableList<GeoPoint>>()
            points.forEachIndexed { index, point ->
                if (index == 0 || point.time - points[index - 1].time > 90_000) segments.add(mutableListOf())
                segments.last().add(GeoPoint(point.latitude, point.longitude))
            }
            for (segment in segments.filter { it.size >= 2 }) recordingOverlay.add(Polyline(map).apply {
                setPoints(segment); title = "Recorded trail"; outlinePaint.color = Color.rgb(21, 101, 192); outlinePaint.strokeWidth = dp(4).toFloat()
            })
            map.invalidate()
        }
        map.invalidate() // Also remove the live segment after a notification stop or stale fix.
        recordStatus.text = if (running) if (recordedPointCount == 0) "● Recording • waiting for GPS" else "● Recording • $recordedPointCount points • every 30 seconds" else ""
        recordStatus.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        recordStatus.setTextColor(if (running) Color.RED else Color.DKGRAY)
    }

    private fun refreshTrails() {
        if (refreshingTrails || destroyed) return
        refreshingTrails = true
        trailStatus.visibility = android.view.View.VISIBLE
        trailStatus.text = if (trailDocument == null) "Loading trails…" else "Refreshing trails…"
        val needsCache = trailDocument == null
        trailExecutor.execute {
            if (needsCache) {
                trailRepository.cached()?.let { cached -> runOnUiThread {
                    if (!destroyed) { showTrails(cached); trailStatus.text = "${cached.name} • cached • refreshing…" }
                } }
            }
            val result = runCatching { trailRepository.refresh() }
            runOnUiThread {
                if (!destroyed) {
                    refreshingTrails = false
                    result.fold(onSuccess = { document ->
                        showTrails(document)
                        trailStatus.text = ""
                        trailStatus.visibility = android.view.View.GONE
                    }, onFailure = {
                        trailStatus.visibility = android.view.View.VISIBLE
                        trailStatus.text = if (trailDocument != null) "${trailDocument!!.name} • cached • refresh failed"
                                           else "Trails unavailable — check connection and map sharing"
                    })
                }
            }
        }
    }
    private fun showTrails(document: TrailKml.Document) {
        trailDocument = document
        trailOverlay.items.clear()
        for (feature in document.features.sortedBy { if (it.kind == "Polygon") 0 else if (it.kind == "LineString") 1 else 2 }) {
            val points = feature.points.map { GeoPoint(it.latitude, it.longitude) }
            when (feature.kind) {
                "LineString" -> trailOverlay.add(Polyline(map).apply {
                    setPoints(points)
                    title = feature.name
                    outlinePaint.color = feature.style.lineColor
                    outlinePaint.strokeWidth = dp(feature.style.width.toInt().coerceAtLeast(2)).toFloat()
                })
                "Polygon" -> trailOverlay.add(Polygon(map).apply {
                    setPoints(points)
                    holes = feature.holes.map { ring -> ring.map { GeoPoint(it.latitude, it.longitude) } }
                    title = feature.name
                    fillPaint.color = if (feature.style.fill) feature.style.fillColor else Color.TRANSPARENT
                    outlinePaint.color = if (feature.style.outline) feature.style.lineColor else Color.TRANSPARENT
                    outlinePaint.strokeWidth = dp(2).toFloat()
                })
                "Point" -> trailOverlay.add(Marker(map).apply {
                    position = points.first()
                    title = feature.name
                    val resource = when (feature.style.iconId) {
                        "1763" -> R.drawable.mymaps_1763
                        "1899" -> R.drawable.mymaps_1899
                        "1205" -> R.drawable.mymaps_1205
                        "503" -> R.drawable.mymaps_503
                        else -> 0
                    }
                    if (resource != 0) {
                        val bitmap = android.graphics.BitmapFactory.decodeResource(resources, resource)
                        val width = dp((28 * feature.style.iconScale).toInt())
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, width, (width * bitmap.height.toFloat() / bitmap.width).toInt().coerceAtLeast(1), true)
                        icon = android.graphics.drawable.BitmapDrawable(resources, scaled).apply {
                            if (feature.style.iconId == "503") setTint(feature.style.iconColor)
                        }
                    }
                    setAnchor(Marker.ANCHOR_CENTER, if (feature.style.iconId == "1763") Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
                })
            }
        }
        trailOverlay.isEnabled = trailsVisible
        map.invalidate()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hasPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocationPermission() = requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    private fun refreshStatus() {
        recenter.imageTintList = android.content.res.ColorStateList.valueOf(if (following) Color.rgb(21, 101, 192) else Color.DKGRAY)
        status.text = when {
            !hasPermission() -> "Location permission needed — tap Recenter"
            current == null -> "Waiting for location — check that Location is enabled"
            following -> "Following GPS • accuracy ±${current!!.accuracy.toInt()} m"
            else -> "Map paused • tap Recenter to follow GPS"
        }
    }
    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!active || !hasPermission()) { refreshStatus(); return }
        manager.removeUpdates(this)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
        var newest: Location? = null
        for (provider in providers) {
            if (!manager.allProviders.contains(provider) || !manager.isProviderEnabled(provider)) continue
            try {
                manager.requestLocationUpdates(provider, 1000L, 2f, this)
                val cached = manager.getLastKnownLocation(provider)
                if (cached != null && (newest == null || cached.elapsedRealtimeNanos > newest.elapsedRealtimeNanos)) newest = cached
            } catch (_: SecurityException) { }
        }
        newest?.let { if (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 120_000_000_000L) onLocationChanged(it) }
        refreshStatus()
    }
    override fun onLocationChanged(location: Location) {
        val previous = current
        if (previous != null && location.elapsedRealtimeNanos < previous.elapsedRealtimeNanos) return
        current = location
        if (following) map.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        map.invalidate()
        refreshStatus()
    }
    override fun onProviderEnabled(provider: String) { startLocation() }
    override fun onProviderDisabled(provider: String) { current = null; map.invalidate(); startLocation() }
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) startLocation()
    }
    override fun onResume() { super.onResume(); active = true; phoneHeading.start(); map.onResume(); startLocation(); recordingHandler.removeCallbacks(recordingTick); recordingHandler.post(recordingTick) }
    override fun onPause() {
        active = false
        phoneHeading.stop()
        recordingHandler.removeCallbacks(recordingTick)
        manager.removeUpdates(this)
        prefs.edit().putFloat("zoom", map.zoomLevelDouble.toFloat()).putFloat("lat", map.mapCenter.latitude.toFloat()).putFloat("lon", map.mapCenter.longitude.toFloat()).apply()
        map.onPause()
        super.onPause()
    }
    override fun onDestroy() { destroyed = true; trailExecutor.shutdownNow(); map.onDetach(); super.onDestroy() }
}
