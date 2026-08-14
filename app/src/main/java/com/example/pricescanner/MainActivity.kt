package com.example.pricescanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.pricescanner.scanner.PriceImageAnalyzer
import com.example.pricescanner.scanner.PriceReading
import com.example.pricescanner.scanner.StablePrice
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var scanTarget: View
    private lateinit var tvScanStatus: TextView
    private lateinit var tvOriginalPrice: TextView
    private lateinit var tvFinalPrice: TextView
    private lateinit var tvSavings: TextView
    private lateinit var etCustomDiscount: EditText
    private lateinit var btnTorch: MaterialButton
    private lateinit var btnRescan: MaterialButton
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var priceAnalyzer: PriceImageAnalyzer? = null
    private var currentPrice: Double = 0.0
    private var selectedDiscount: Int = 30
    private var isUpdatingDiscount = false
    private var scanLocked = false
    private var cameraStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        scanTarget = findViewById(R.id.scanTarget)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvOriginalPrice = findViewById(R.id.tvOriginalPrice)
        tvFinalPrice = findViewById(R.id.tvFinalPrice)
        tvSavings = findViewById(R.id.tvSavings)
        etCustomDiscount = findViewById(R.id.etCustomDiscount)
        btnTorch = findViewById(R.id.btnTorch)
        btnRescan = findViewById(R.id.btnRescan)

        viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER
        cameraExecutor = Executors.newSingleThreadExecutor()

        viewFinder.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING && camera != null) {
                updateAnalyzerGeometry()
                scanTarget.post { focusOnScanTarget() }
            }
        }

        val geometryListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateAnalyzerGeometry()
        }
        viewFinder.addOnLayoutChangeListener(geometryListener)
        scanTarget.addOnLayoutChangeListener(geometryListener)

        setupDiscountControls()
        setupScannerControls()
        renderEmptyPrice()

        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupDiscountControls() {
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupDiscount)
        toggleGroup.check(R.id.btn30)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isUpdatingDiscount) return@addOnButtonCheckedListener

            selectedDiscount = when (checkedId) {
                R.id.btn20 -> 20
                R.id.btn30 -> 30
                R.id.btn50 -> 50
                else -> selectedDiscount
            }

            isUpdatingDiscount = true
            etCustomDiscount.setText(selectedDiscount.toString())
            isUpdatingDiscount = false
            updatePriceUI()
        }

        etCustomDiscount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingDiscount) return

                selectedDiscount = (s?.toString()?.toIntOrNull() ?: 0).coerceIn(0, 100)

                isUpdatingDiscount = true
                when (selectedDiscount) {
                    20 -> toggleGroup.check(R.id.btn20)
                    30 -> toggleGroup.check(R.id.btn30)
                    50 -> toggleGroup.check(R.id.btn50)
                    else -> toggleGroup.clearChecked()
                }
                isUpdatingDiscount = false
                updatePriceUI()
            }
        })
    }

    private fun setupScannerControls() {
        btnRescan.isEnabled = false
        btnRescan.setOnClickListener { resetScanner() }

        btnTorch.isCheckable = true
        btnTorch.isEnabled = false
        btnTorch.setOnClickListener {
            val enabled = btnTorch.isChecked
            camera?.cameraControl?.enableTorch(enabled)
            btnTorch.text = if (enabled) getString(R.string.torch_on) else getString(R.string.torch_off)
        }

        // A tap on the green target explicitly refocuses and remeters that area.
        scanTarget.setOnClickListener { focusOnScanTarget() }
    }

    private fun startCamera() {
        if (cameraStarted || !allPermissionsGranted()) return
        if (viewFinder.width <= 0 || viewFinder.height <= 0) {
            viewFinder.post { startCamera() }
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            priceAnalyzer?.close()
            priceAnalyzer = PriceImageAnalyzer(
                onCandidate = ::onCandidate,
                onStablePrice = ::onStablePrice
            )

            updateAnalyzerGeometry()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, requireNotNull(priceAnalyzer)) }

            try {
                provider.unbindAll()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                val viewPort = viewFinder.viewPort

                camera = if (viewPort != null) {
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .setViewPort(viewPort)
                        .build()
                    provider.bindToLifecycle(this, selector, group)
                } else {
                    provider.bindToLifecycle(this, selector, preview, analysis)
                }

                cameraStarted = true
                configureCameraControls()
            } catch (e: Exception) {
                tvScanStatus.text = getString(R.string.camera_start_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun configureCameraControls() {
        val activeCamera = camera ?: return
        val hasFlash = activeCamera.cameraInfo.hasFlashUnit()
        btnTorch.isEnabled = hasFlash
        if (!hasFlash) {
            btnTorch.isChecked = false
            btnTorch.text = getString(R.string.no_torch)
        }
    }

    private fun updateAnalyzerGeometry() {
        if (viewFinder.width <= 0 || viewFinder.height <= 0 || scanTarget.width <= 0 || scanTarget.height <= 0) return

        val previewLocation = IntArray(2)
        val targetLocation = IntArray(2)
        viewFinder.getLocationInWindow(previewLocation)
        scanTarget.getLocationInWindow(targetLocation)

        val left = (targetLocation[0] - previewLocation[0]).toFloat()
        val top = (targetLocation[1] - previewLocation[1]).toFloat()
        val rect = RectF(left, top, left + scanTarget.width, top + scanTarget.height)
        priceAnalyzer?.updatePreviewGeometry(viewFinder.width, viewFinder.height, rect)
    }

    private fun focusOnScanTarget() {
        val activeCamera = camera ?: return
        if (viewFinder.width <= 0 || viewFinder.height <= 0) return

        val previewLocation = IntArray(2)
        val targetLocation = IntArray(2)
        viewFinder.getLocationInWindow(previewLocation)
        scanTarget.getLocationInWindow(targetLocation)

        val centerX = targetLocation[0] - previewLocation[0] + scanTarget.width / 2f
        val centerY = targetLocation[1] - previewLocation[1] + scanTarget.height / 2f

        val point = viewFinder.meteringPointFactory.createPoint(centerX, centerY, 0.18f)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()

        activeCamera.cameraControl.startFocusAndMetering(action)
    }

    private fun onCandidate(reading: PriceReading?) {
        runOnUiThread {
            if (scanLocked) return@runOnUiThread
            tvScanStatus.text = when {
                reading == null -> getString(R.string.scan_searching)
                reading.score >= 150.0 -> getString(R.string.scan_confirming)
                else -> getString(R.string.scan_analyzing)
            }
        }
    }

    private fun onStablePrice(stable: StablePrice) {
        runOnUiThread {
            if (scanLocked) return@runOnUiThread

            scanLocked = true
            priceAnalyzer?.lock()
            currentPrice = stable.value
            btnRescan.isEnabled = true

            val confidence = (stable.confidence * 100).toInt().coerceIn(0, 99)
            tvScanStatus.text = getString(R.string.scan_locked, confidence)
            updatePriceUI()
        }
    }

    private fun resetScanner() {
        scanLocked = false
        currentPrice = 0.0
        btnRescan.isEnabled = false
        priceAnalyzer?.reset()
        tvScanStatus.text = getString(R.string.scan_searching)
        renderEmptyPrice()
        focusOnScanTarget()
    }

    private fun updatePriceUI() {
        if (currentPrice <= 0.0) {
            renderEmptyPrice()
            return
        }

        val savings = currentPrice * (selectedDiscount / 100.0)
        val finalPrice = currentPrice - savings

        tvOriginalPrice.text = getString(R.string.scanned_price, currentPrice)
        tvFinalPrice.text = String.format(Locale.getDefault(), "%.2f €", finalPrice)
        tvSavings.text = getString(R.string.savings, savings, selectedDiscount)
    }

    private fun renderEmptyPrice() {
        tvOriginalPrice.text = getString(R.string.scanned_price_empty)
        tvFinalPrice.text = getString(R.string.price_empty)
        tvSavings.text = getString(R.string.savings_empty)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            tvScanStatus.text = getString(R.string.camera_permission_required)
        }
    }

    override fun onDestroy() {
        priceAnalyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
