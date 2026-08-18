package com.example.hackofiesta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.text.SimpleDateFormat
import java.util.*

class AmbulanceRouteActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // UI elements
    private lateinit var routeSpinner: Spinner
    private lateinit var txtInstructions: TextView
    private lateinit var btnModeSim: Button
    private lateinit var btnModeLiveGps: Button
    private lateinit var simControlsLayout: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnReset: Button
    private lateinit var lblSpeed: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var layoutSignalList: LinearLayout
    private lateinit var txtScannerLog: TextView

    // Location / Route State
    private enum class Mode { SIMULATION, LIVE_GPS }
    private var currentMode = Mode.SIMULATION

    private var activeRoutePoints = mutableListOf<LatLng>()
    private var simIndex = 0
    private var isSimRunning = false
    private var speedMultiplier = 1

    private var ambulanceMarker: Marker? = null
    private var customStartMarker: Marker? = null
    private var customEndMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Predefined junctions
    private val junctionsList = listOf(
        Junction("Cyber Towers Junction", LatLng(17.4504, 78.3808)),
        Junction("Mindspace Junction", LatLng(17.4428, 78.3813)),
        Junction("IKEA Junction", LatLng(17.4385, 78.3755)),
        Junction("Gachibowli Junction", LatLng(17.4401, 78.3489)),
        Junction("Kondapur Junction", LatLng(17.4623, 78.3697)),
        Junction("Madhapur Junction", LatLng(17.4486, 78.3908)),
        Junction("Jubilee Hills Checkpost", LatLng(17.4331, 78.4069))
    )

    private val junctionMarkers = mutableMapOf<String, Marker>()

    // Runnables for updates
    private val handler = Handler(Looper.getMainLooper())
    private var gpsRunnable: Runnable? = null

    private val simRunnable = object : Runnable {
        override fun run() {
            if (!isSimRunning || activeRoutePoints.isEmpty()) return

            if (simIndex < activeRoutePoints.size) {
                val currentLatLng = activeRoutePoints[simIndex]
                updateAmbulanceLocation(currentLatLng)

                simIndex += speedMultiplier
                if (simIndex >= activeRoutePoints.size) {
                    simIndex = activeRoutePoints.size - 1
                    updateAmbulanceLocation(activeRoutePoints[simIndex])
                    stopSimulation()
                    appendLog("⚠️ SIMULATION COMPLETED! Ambulance has reached its destination.")
                } else {
                    handler.postDelayed(this, 300)
                }
            }
        }
    }

    data class Junction(
        val name: String,
        val latLng: LatLng,
        var distance: Float = Float.MAX_VALUE,
        var isGreen: Boolean = false,
        var previouslyGreen: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ambulance_route)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupUI()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.ambulanceMap) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupUI() {
        val backBtn = findViewById<Button>(R.id.backBtn)
        backBtn.setOnClickListener { finish() }

        routeSpinner = findViewById(R.id.routeSpinner)
        txtInstructions = findViewById(R.id.txtInstructions)
        btnModeSim = findViewById(R.id.btnModeSim)
        btnModeLiveGps = findViewById(R.id.btnModeLiveGps)
        simControlsLayout = findViewById(R.id.simControlsLayout)
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnReset = findViewById(R.id.btnReset)
        lblSpeed = findViewById(R.id.lblSpeed)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        layoutSignalList = findViewById(R.id.layoutSignalList)
        txtScannerLog = findViewById(R.id.txtScannerLog)

        // Setup Spinners
        val routes = arrayOf(
            "Gachibowli ➔ Cyber Towers",
            "Kondapur ➔ Jubilee Hills Checkpost",
            "Custom Route (Tap Map)"
        )
        val routeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, routes)
        routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        routeSpinner.adapter = routeAdapter

        routeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 2) {
                    txtInstructions.text = "Tap two locations on the map to set a Custom Start and End route."
                    if (::mMap.isInitialized) {
                        clearRouteAndSimulation()
                    }
                } else {
                    txtInstructions.text = "Preset selected. Press 'Start' to begin the simulation."
                    if (::mMap.isInitialized) {
                        loadPresetRoute(position)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Mode Toggles
        btnModeSim.setOnClickListener {
            switchMode(Mode.SIMULATION)
        }
        btnModeLiveGps.setOnClickListener {
            switchMode(Mode.LIVE_GPS)
        }

        // Sim controls
        btnStart.setOnClickListener {
            startSimulation()
        }
        btnPause.setOnClickListener {
            pauseSimulation()
        }
        btnReset.setOnClickListener {
            resetSimulation()
        }

        // Speed seek bar
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speedMultiplier = progress + 1
                lblSpeed.text = "Speed: ${speedMultiplier}x"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Initial UI States
        switchMode(Mode.SIMULATION)
        updateSignalListUI()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = false
        mMap.uiSettings.isMapToolbarEnabled = false

        // Place junction markers
        val redIcon = bitmapDescriptorFromVector(this, R.drawable.ic_traffic_light_red)
        for (junction in junctionsList) {
            val marker = mMap.addMarker(
                MarkerOptions()
                    .position(junction.latLng)
                    .title(junction.name)
                    .icon(redIcon)
                    .anchor(0.5f, 0.5f)
            )
            marker?.let {
                junctionMarkers[junction.name] = it
            }
        }

        // Recenter to Hyderabad Gachibowli
        val hydCenter = LatLng(17.4435, 78.3772)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(hydCenter, 14f))

        // Set map click listener for custom routing
        mMap.setOnMapClickListener { latLng ->
            if (currentMode == Mode.LIVE_GPS) {
                Toast.makeText(this, "Custom route selection disabled in Live GPS mode.", Toast.LENGTH_SHORT).show()
                return@setOnMapClickListener
            }

            if (customStartMarker == null) {
                customStartMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("Custom Start")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                Toast.makeText(this, "Start point set. Tap map to set End point.", Toast.LENGTH_SHORT).show()
            } else if (customEndMarker == null) {
                customEndMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("Custom End")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                Toast.makeText(this, "End point set. Custom route calculated.", Toast.LENGTH_SHORT).show()

                routeSpinner.setSelection(2) // Set to Custom
                calculateCustomRoute()
            } else {
                // Reset custom markers
                customStartMarker?.remove()
                customStartMarker = null
                customEndMarker?.remove()
                customEndMarker = null

                customStartMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("Custom Start")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                Toast.makeText(this, "Route reset. New Start point set. Tap map to set End.", Toast.LENGTH_SHORT).show()
            }
        }

        // Draw default preset route on map ready
        loadPresetRoute(0)
    }

    private fun switchMode(mode: Mode) {
        currentMode = mode
        stopSimulation()
        stopGpsTracking()

        if (mode == Mode.SIMULATION) {
            btnModeSim.setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorContainer))
            btnModeLiveGps.setBackgroundColor(Color.TRANSPARENT)
            simControlsLayout.visibility = View.VISIBLE
            txtInstructions.visibility = View.VISIBLE
            routeSpinner.visibility = View.VISIBLE
            // Load selected preset or custom route
            if (::mMap.isInitialized) {
                loadPresetRoute(routeSpinner.selectedItemPosition)
            }
        } else {
            btnModeLiveGps.setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorContainer))
            btnModeSim.setBackgroundColor(Color.TRANSPARENT)
            simControlsLayout.visibility = View.GONE
            txtInstructions.text = "GPS Tracking Mode active. Overriding signals in 500m radius of your actual device location."
            clearRouteAndSimulation()
            startGpsTracking()
        }
    }

    private fun loadPresetRoute(index: Int) {
        clearRouteAndSimulation()

        val points = when (index) {
            0 -> listOf(
                LatLng(17.4401, 78.3489), // Gachibowli
                LatLng(17.4385, 78.3755), // IKEA
                LatLng(17.4428, 78.3813), // Mindspace
                LatLng(17.4504, 78.3808)  // Cyber Towers
            )
            1 -> listOf(
                LatLng(17.4623, 78.3697), // Kondapur
                LatLng(17.4504, 78.3808), // Cyber Towers
                LatLng(17.4486, 78.3908), // Madhapur
                LatLng(17.4331, 78.4069)  // Jubilee Hills Checkpost
            )
            else -> return
        }

        activeRoutePoints = interpolatePath(points).toMutableList()
        drawRoutePolyline()
        resetSimulation()

        // Move camera to bounds
        if (activeRoutePoints.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.Builder()
            activeRoutePoints.forEach { boundsBuilder.include(it) }
            val bounds = boundsBuilder.build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
    }

    private fun calculateCustomRoute() {
        val start = customStartMarker?.position ?: return
        val end = customEndMarker?.position ?: return

        activeRoutePoints = interpolatePath(listOf(start, end)).toMutableList()
        drawRoutePolyline()
        resetSimulation()

        val boundsBuilder = LatLngBounds.Builder()
        boundsBuilder.include(start)
        boundsBuilder.include(end)
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
    }

    private fun drawRoutePolyline() {
        routePolyline?.remove()
        val polylineOptions = PolylineOptions()
            .addAll(activeRoutePoints)
            .width(12f)
            .color(Color.parseColor("#2563EB")) // Premium blue
            .geodesic(true)
        routePolyline = mMap.addPolyline(polylineOptions)
    }

    private fun clearRouteAndSimulation() {
        stopSimulation()
        if (::mMap.isInitialized) {
            routePolyline?.remove()
        }
        routePolyline = null
        activeRoutePoints.clear()
        ambulanceMarker?.remove()
        ambulanceMarker = null
        simIndex = 0

        // Reset signal states
        if (::mMap.isInitialized) {
            val redIcon = bitmapDescriptorFromVector(this, R.drawable.ic_traffic_light_red)
            for (junction in junctionsList) {
                junction.isGreen = false
                junction.previouslyGreen = false
                junction.distance = Float.MAX_VALUE
                junctionMarkers[junction.name]?.setIcon(redIcon)
            }
        }
        updateSignalListUI()
    }

    private fun startSimulation() {
        if (activeRoutePoints.isEmpty()) {
            Toast.makeText(this, "Please configure a route first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSimRunning) return
        isSimRunning = true
        handler.post(simRunnable)
        appendLog("🚑 SIMULATION STARTED: Ambulance route dispatched.")
    }

    private fun pauseSimulation() {
        if (!isSimRunning) return
        isSimRunning = false
        handler.removeCallbacks(simRunnable)
        appendLog("⏸️ SIMULATION PAUSED")
    }

    private fun resetSimulation() {
        stopSimulation()
        simIndex = 0
        if (activeRoutePoints.isNotEmpty()) {
            updateAmbulanceLocation(activeRoutePoints[0])
        }
        appendLog("🔄 SIMULATION RESET")
    }

    private fun stopSimulation() {
        isSimRunning = false
        handler.removeCallbacks(simRunnable)
    }

    private fun startGpsTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        // Simple pull loop every 2 seconds
        gpsRunnable = object : Runnable {
            override fun run() {
                if (currentMode == Mode.LIVE_GPS) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        location?.let {
                            val currentLatLng = LatLng(it.latitude, it.longitude)
                            updateAmbulanceLocation(currentLatLng)
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                        }
                    }
                    handler.postDelayed(this, 2000)
                }
            }
        }
        handler.post(gpsRunnable!!)
        appendLog("📡 LIVE GPS TRACKING ENGAGED: Monitoring location coordinates...")
    }

    private fun stopGpsTracking() {
        gpsRunnable?.let {
            handler.removeCallbacks(it)
            gpsRunnable = null
        }
    }

    private fun updateAmbulanceLocation(latLng: LatLng) {
        if (!::mMap.isInitialized) return

        // Place or move ambulance marker
        if (ambulanceMarker == null) {
            val ambIcon = bitmapDescriptorFromVector(this, R.drawable.ic_ambulance)
            ambulanceMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Ambulance (Priority Code: AMB-TX-991)")
                    .icon(ambIcon)
                    .anchor(0.5f, 0.5f)
            )
        } else {
            ambulanceMarker?.position = latLng
        }

        // Priority Override Logic
        val greenIcon = bitmapDescriptorFromVector(this, R.drawable.ic_traffic_light_green)
        val redIcon = bitmapDescriptorFromVector(this, R.drawable.ic_traffic_light_red)

        for (junction in junctionsList) {
            val distResults = FloatArray(1)
            Location.distanceBetween(
                latLng.latitude, latLng.longitude,
                junction.latLng.latitude, junction.latLng.longitude,
                distResults
            )
            val distance = distResults[0]
            junction.distance = distance

            val withinRange = distance < 500.0f
            if (withinRange) {
                junction.isGreen = true
                junctionMarkers[junction.name]?.setIcon(greenIcon)

                // Log transponder override trigger on entry
                if (!junction.previouslyGreen) {
                    junction.previouslyGreen = true
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    appendLog("⚡ [$time] OVERRIDE: ${junction.name} scanner detected Ambulance!\n   ↳ Transponder ID: AMB-TX-991\n   ↳ Signal overridden to GREEN (Priority Lock)")
                }
            } else {
                junction.isGreen = false
                junctionMarkers[junction.name]?.setIcon(redIcon)

                // Log cleared override when passing out of range
                if (junction.previouslyGreen) {
                    junction.previouslyGreen = false
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    appendLog("ℹ️ [$time] OVERRIDE RELEASED: Ambulance cleared ${junction.name}. Signal reset to normal.")
                }
            }
        }

        updateSignalListUI()
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            if (txtScannerLog.text.contains("Transponder inactive") || txtScannerLog.text.contains("Ready for simulation")) {
                txtScannerLog.text = msg
            } else {
                txtScannerLog.append("\n$msg")
            }

            // Scroll log to bottom
            val scroll = txtScannerLog.parent.parent as? NestedScrollView
            scroll?.post {
                scroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateSignalListUI() {
        runOnUiThread {
            layoutSignalList.removeAllViews()
            for (junction in junctionsList) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dpToPx(6), 0, dpToPx(6))
                    }
                    gravity = Gravity.CENTER_VERTICAL
                }

                val nameTxt = TextView(this).apply {
                    text = junction.name
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val distTxt = TextView(this).apply {
                    text = if (junction.distance == Float.MAX_VALUE) {
                        "---"
                    } else if (junction.distance < 1000) {
                        "${junction.distance.toInt()}m"
                    } else {
                        String.format("%.1fkm", junction.distance / 1000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, dpToPx(16), 0)
                    }
                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    textSize = 13f
                }

                // Traffic Light color circle
                val statusIndicator = View(this).apply {
                    val size = dpToPx(16)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    val bgDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (junction.isGreen) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
                    }
                    background = bgDrawable
                }

                row.addView(nameTxt)
                row.addView(distTxt)
                row.addView(statusIndicator)
                layoutSignalList.addView(row)
            }
        }
    }

    private fun interpolatePath(points: List<LatLng>, stepDistanceMeters: Double = 40.0): List<LatLng> {
        if (points.size < 2) return points
        val path = mutableListOf<LatLng>()
        path.add(points[0])

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]
            val results = FloatArray(1)
            Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
            val segmentDistance = results[0].toDouble()

            var numSteps = (segmentDistance / stepDistanceMeters).toInt()
            if (numSteps < 1) numSteps = 1

            for (step in 1..numSteps) {
                val fraction = step.toDouble() / numSteps
                val lat = start.latitude + (end.latitude - start.latitude) * fraction
                val lon = start.longitude + (end.longitude - start.longitude) * fraction
                path.add(LatLng(lat, lon))
            }
        }
        return path
    }

    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)
        vectorDrawable!!.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun getThemeColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsTracking()
        } else {
            Toast.makeText(this, "Location permission is required for Live GPS mode.", Toast.LENGTH_SHORT).show()
            switchMode(Mode.SIMULATION)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimulation()
        stopGpsTracking()
    }
}
