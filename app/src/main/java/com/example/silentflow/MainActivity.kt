package com.example.silentflow

import android.content.Intent
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.acesso.acessobio_android.onboarding.UnicoSDK
import com.acesso.acessobio_android.onboarding.models.PrepareInfo
import com.google.android.material.textfield.TextInputEditText
import io.unico.unicosdkbase.silentauth.domain.model.SilentValidationResult
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * IDPay Silent Flow POC: silent device data collection through the Unico SDK
 * (initializeSDK on app launch + startSilentValidation — no camera involved),
 * followed by an IDPay transaction that is approved silently when the
 * collected device matches the user history.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val USE_CASE = "idpay-silent-flow-poc"
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

    // When the last collection becomes available to the transaction: send
    // confirmation + processing margin.
    private var collectReadyAtMs = 0L
    private var runTransactionAfterCollect = false

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

    /** Runs the silent collection: no camera, no UI. */
    private fun startCollect() {
        val externalUserId = resolveExternalUserId()
        if (externalUserId.isEmpty()) {
            addLog("ERROR: fill the externalUserId (or the CPF)")
            runTransactionAfterCollect = false
            return
        }

        val sdk = UnicoSDK.getInstance()
        if (sdk == null) {
            onCollectFailed(IllegalStateException("UnicoSDK.initializeSDK was not called"))
            return
        }

        mainText.text = getString(R.string.status_collect_preparing)
        lifecycleScope.launch {
            // Resolves once the collection has been sent.
            sdk.startSilentValidation(PrepareInfo(externalUserId, USE_CASE))
                .onSuccess { onCollectSent(externalUserId) }
                .onFailure { onCollectFailed(it) }
        }
    }

    private fun onCollectSent(externalUserId: String) {
        collectReadyAtMs = System.currentTimeMillis() + PocConfig.POST_SEND_WAIT_MS
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        mainText.text = getString(R.string.status_collect_ready, ts)
        addLog("collect: sent for externalUserId=$externalUserId")

        if (runTransactionAfterCollect) {
            runTransactionAfterCollect = false
            createTransactionWhenReady()
        }
    }

    // Without a collection, the transaction simply follows the standard flow
    // with the visual challenge. The SDK is initialized again so the next
    // attempt can succeed if the launch-time initialization failed.
    private fun onCollectFailed(error: Throwable) {
        collectReadyAtMs = 0L
        runTransactionAfterCollect = false
        mainText.text = getString(R.string.status_collect_failed)
        val detail = (error as? SilentValidationResult.Error)
            ?.let { "${it.code} ${it.message}" }
            ?: error.message
        addLog("collect FAILED: $detail")

        SilentFlowApplication.initializeUnicoSdk(this)
        addLog("SDK re-initialized — try the collection again")
    }

    // ----------------------------------------------------------- transaction

    /**
     * Creates the transaction. Right after a collection, the remaining
     * processing margin is absorbed into the loading screen.
     */
    private fun createTransactionWhenReady() {
        val missing = buildList {
            if (cpfInput.text.isNullOrBlank()) add("CPF")
            if (binInput.text.isNullOrBlank()) add("binDigits")
            if (lastDigitsInput.text.isNullOrBlank()) add("lastDigits")
            if (bearerTokenInput.text.isNullOrBlank()) add("Bearer token")
            if (PocConfig.COMPANY_ID.startsWith("YOUR_")) add("COMPANY_ID (PocConfig.kt)")
        }
        if (missing.isNotEmpty()) {
            addLog("ERROR: missing ${missing.joinToString(", ")}")
            return
        }

        showOverlayProcessing(getString(R.string.overlay_processing))
        val remainingMs = collectReadyAtMs - System.currentTimeMillis()
        if (remainingMs > 0) {
            addLog("transaction: waiting ${remainingMs}ms of processing margin")
            mainHandler.postDelayed({ createTransaction() }, remainingMs)
        } else {
            createTransaction()
        }
    }

    private fun createTransaction() {
        val externalUserId = resolveExternalUserId()
        val cpf = cpfInput.text.toString().trim()
        val bin = binInput.text.toString().trim()
        val lastDigits = lastDigitsInput.text.toString().trim()
        val token = bearerTokenInput.text.toString().trim()

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
