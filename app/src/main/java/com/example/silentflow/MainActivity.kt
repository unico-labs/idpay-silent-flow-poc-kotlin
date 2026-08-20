package com.example.silentflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.acesso.acessobio_android.AcessoBioListener
import com.acesso.acessobio_android.onboarding.AcessoBio
import com.acesso.acessobio_android.onboarding.camera.CameraListener
import com.acesso.acessobio_android.onboarding.camera.UnicoCheckCameraOpener
import com.acesso.acessobio_android.onboarding.models.Environment
import com.acesso.acessobio_android.services.dto.ErrorBio
import com.acesso.acessobio_android.services.dto.PrepareInfo
import com.google.android.material.textfield.TextInputEditText
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * IDPay Silent Flow POC: silent device data collection through the Unico SDK
 * (prepareCamera + PrepareInfo — the camera is never opened), followed by an
 * IDPay transaction that is approved silently when the collected device
 * matches the user history.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val USE_CASE = "idpay-silent-flow-poc"

        // Window for the SDK fire-and-forget upload to leave the device. The
        // countdown runs in background; the user only sees a loading screen if
        // the transaction is requested while time is still remaining.
        private const val COLLECT_GRACE_MS = 5_000L
    }

    private lateinit var mainText: TextView
    private lateinit var externalUserIdInput: TextInputEditText
    private lateinit var cpfInput: TextInputEditText
    private lateinit var binInput: TextInputEditText
    private lateinit var lastDigitsInput: TextInputEditText
    private lateinit var bearerTokenInput: TextInputEditText
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var overlay: View
    private lateinit var overlaySpinner: ProgressBar
    private lateinit var overlayIcon: TextView
    private lateinit var overlayText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var collectReadyAtMs = 0L
    private var collectGraceDeadlineMs = 0L
    private var collectGeneration = 0
    private var runTransactionAfterCollect = false

    private val bioListener = object : AcessoBioListener {
        override fun onErrorAcessoBio(error: ErrorBio?) =
            runOnUiThread { addLog("SDK error: ${error?.description}") }

        override fun onUserClosedCameraManually() = Unit
        override fun onSystemClosedCameraTimeoutSession() = Unit
        override fun onSystemChangedTypeCameraTimeoutFaceInference() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainText = findViewById(R.id.mainText)
        externalUserIdInput = findViewById(R.id.externalUserIdInput)
        cpfInput = findViewById(R.id.cpfInput)
        binInput = findViewById(R.id.binInput)
        lastDigitsInput = findViewById(R.id.lastDigitsInput)
        bearerTokenInput = findViewById(R.id.bearerTokenInput)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        overlay = findViewById(R.id.overlay)
        overlaySpinner = findViewById(R.id.overlaySpinner)
        overlayIcon = findViewById(R.id.overlayIcon)
        overlayText = findViewById(R.id.overlayText)

        findViewById<TextView>(R.id.clearLogButton).setOnClickListener {
            logTextView.text = getString(R.string.logs_placeholder)
        }

        ensureCameraPermission()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    // ---------------------------------------------------------- button hooks

    fun collectDeviceData(view: View) {
        runTransactionAfterCollect = false
        startCollect()
    }

    fun createSilentTransaction(view: View) {
        createTransactionWhenReady()
    }

    fun runFullFlow(view: View) {
        runTransactionAfterCollect = true
        startCollect()
    }

    // ------------------------------------------------------------ collection

    // The identifier typed on screen; the CPF is the fallback when it is empty.
    // In a real integration this is whatever id the client has for the user.
    private fun resolveExternalUserId(): String =
        externalUserIdInput.text.toString().trim()
            .ifEmpty { cpfInput.text.toString().trim() }

    /** Starts the silent collection. Always runs in background: no camera UI. */
    private fun startCollect() {
        val externalUserId = resolveExternalUserId()
        if (externalUserId.isEmpty()) {
            addLog("ERROR: fill the externalUserId (or the CPF)")
            runTransactionAfterCollect = false
            return
        }

        mainText.text = getString(R.string.status_collect_preparing)
        addLog("collect: prepareCamera + PrepareInfo($externalUserId, $USE_CASE)")

        AcessoBio(this, bioListener)
            .setEnvironment(Environment.UAT)
            .build()
            .prepareCamera(
                UnicoConfig(),
                object : CameraListener {
                    override fun onCameraReady(opener: UnicoCheckCameraOpener.Camera?) =
                        runOnUiThread { onCollectReady() }

                    override fun onCameraFailed(message: String?) =
                        runOnUiThread { onCollectFailed(message) }
                },
                PrepareInfo(externalUserId, USE_CASE),
            )
    }

    private fun onCollectReady() {
        // The camera is intentionally NOT opened — the collection continues in
        // background. Start counting the upload grace window from here.
        collectReadyAtMs = System.currentTimeMillis()
        collectGraceDeadlineMs = collectReadyAtMs + COLLECT_GRACE_MS
        val generation = ++collectGeneration

        mainText.text = getString(R.string.status_collect_uploading)
        addLog("collect: started in background (grace window: ${COLLECT_GRACE_MS}ms)")

        mainHandler.postDelayed({
            if (generation == collectGeneration) {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                mainText.text = getString(R.string.status_collect_ready, ts)
                addLog("collect: upload window closed — device data ready")
            }
        }, COLLECT_GRACE_MS)

        if (runTransactionAfterCollect) {
            runTransactionAfterCollect = false
            createTransactionWhenReady()
        }
    }

    private fun onCollectFailed(message: String?) {
        collectGeneration++
        mainText.text = getString(R.string.status_collect_failed)
        addLog("collect FAILED: $message")
        runTransactionAfterCollect = false
        hideOverlay()
    }

    // ----------------------------------------------------------- transaction

    /**
     * Creates the transaction respecting the collection grace window: if the
     * upload still needs time, that wait is absorbed into the loading screen;
     * otherwise the request goes out immediately.
     */
    private fun createTransactionWhenReady() {
        showOverlayProcessing(getString(R.string.overlay_processing))

        val now = System.currentTimeMillis()
        val remainingMs = collectGraceDeadlineMs - now
        val sinceCollectMs = now - collectReadyAtMs
        when {
            remainingMs > 0 -> {
                addLog("transaction: requested ${sinceCollectMs}ms after collect → absorbing ${remainingMs}ms into loading")
                mainHandler.postDelayed({ createTransaction() }, remainingMs)
            }
            collectReadyAtMs > 0 -> {
                addLog("transaction: upload window already closed (collect ${sinceCollectMs}ms ago) → no extra wait")
                createTransaction()
            }
            else -> {
                addLog("transaction: no collection in this session → sending as-is")
                createTransaction()
            }
        }
    }

    private fun createTransaction() {
        val externalUserId = resolveExternalUserId()
        val cpf = cpfInput.text.toString().trim()
        val bin = binInput.text.toString().trim()
        val lastDigits = lastDigitsInput.text.toString().trim()
        val token = bearerTokenInput.text.toString().trim()

        val missing = mutableListOf<String>()
        if (cpf.isEmpty()) missing.add("CPF")
        if (bin.isEmpty()) missing.add("binDigits")
        if (lastDigits.isEmpty()) missing.add("lastDigits")
        if (token.isEmpty()) missing.add("Bearer token")
        if (missing.isNotEmpty()) {
            addLog("ERROR: missing ${missing.joinToString(", ")}")
            hideOverlay()
            return
        }
        if (PocConfig.COMPANY_ID.startsWith("YOUR_")) {
            addLog("ERROR: fill COMPANY_ID in PocConfig.kt")
            hideOverlay()
            return
        }

        val body = JSONObject()
            .put("identity", JSONObject().put("key", "cpf").put("value", cpf))
            .put("orderNumber", "silent-flow-poc-${System.currentTimeMillis()}")
            .put("company", PocConfig.COMPANY_ID)
            .put("redirectUrl", "silentflow://done?status=fallback-finished")
            .put(
                "card",
                JSONObject()
                    .put("binDigits", bin)
                    .put("lastDigits", lastDigits)
                    .put("expirationDate", "12/28")
                    .put("name", "Silent Flow Poc"),
            )
            .put("value", 10.50)
            .put("additionalInfo", JSONObject().put("externalUserID", externalUserId))

        addLog("transaction: POST ${PocConfig.IDPAY_BASE_URL}/api/public/v1/credit/transaction")

        // In a real integration this request is made by the CLIENT's backend,
        // which then calls the IDPay API server-to-server. The POC skips that
        // hop and calls IDPay directly with a token pasted on the screen.
        Thread {
            try {
                val url = URL("${PocConfig.IDPAY_BASE_URL}/api/public/v1/credit/transaction")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
                runOnUiThread { onTransactionResponse(code, responseBody) }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("request ERROR: ${e.message}")
                    hideOverlay()
                }
            }
        }.start()
    }

    private fun onTransactionResponse(code: Int, body: String) {
        val json = try { JSONObject(body) } catch (e: Exception) { null }
        if (code !in 200..299 || json == null) {
            addLog("HTTP $code: ${body.take(400)}")
            hideOverlay()
            return
        }

        val status = json.optString("status")
        val id = json.optString("id")
        val link = json.optString("link")
        addLog("HTTP $code · id=$id · status=$status")

        when {
            status == "approved" -> {
                addLog("✔ SILENT APPROVAL — no challenge needed")
                showOverlayApproved()
            }
            link.isNotEmpty() -> {
                addLog("challenge required — opening fallback")
                hideOverlay()
                openInCustomTab(Uri.parse(link))
            }
            else -> {
                addLog("status=$status without link — not approved")
                hideOverlay()
            }
        }
    }

    // --------------------------------------------------------------- overlay

    private fun showOverlayProcessing(message: String) {
        overlay.setBackgroundColor(ContextCompat.getColor(this, R.color.unico_dark_navy))
        overlaySpinner.visibility = View.VISIBLE
        overlayIcon.visibility = View.GONE
        overlayText.text = message
        overlay.setOnClickListener(null)
        overlay.visibility = View.VISIBLE
    }

    private fun showOverlayApproved() {
        overlay.setBackgroundColor(ContextCompat.getColor(this, R.color.result_ok))
        overlaySpinner.visibility = View.GONE
        overlayIcon.text = "✔"
        overlayIcon.visibility = View.VISIBLE
        overlayText.text = getString(R.string.overlay_approved)
        overlay.setOnClickListener { hideOverlay() }
        overlay.visibility = View.VISIBLE
        mainHandler.postDelayed({ hideOverlay() }, 3_000)
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
    }

    // --------------------------------------------------------------- helpers

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "silentflow") return
        addLog("deep link: status=${data.getQueryParameter("status")} (challenge return)")
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun openInCustomTab(url: Uri) {
        CustomTabsIntent.Builder()
            .setUrlBarHidingEnabled(true)
            .build()
            .launchUrl(this, url)
    }

    private fun addLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val current = logTextView.text.toString()
        val base = if (current == getString(R.string.logs_placeholder)) "" else "$current\n"
        logTextView.text = "$base[$ts] $message"
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
