// Instally Android SDK
// Track clicks, installs, and revenue from every link.
// https://instally.io

package io.instally.sdk

import android.content.Context
import android.os.Build
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Instally — track clicks, installs, and revenue from every link.
 *
 * ```kotlin
 * // In Application.onCreate() or main Activity.onCreate():
 * Instally.configure(this, appId = "app_xxx", apiKey = "key_xxx")
 * Instally.trackInstall(this)
 * ```
 */
object Instally {

    private const val TAG = "Instally"
    private const val SDK_VERSION = "1.0.0"
    private const val PREFS_NAME = "instally_prefs"
    private const val KEY_TRACKED = "install_tracked"
    private const val KEY_ATTRIBUTION_ID = "attribution_id"
    private const val KEY_MATCHED = "matched"

    private var appId: String? = null
    private var apiKey: String? = null
    private var apiBase = "https://us-central1-instally-5f6fd.cloudfunctions.net/api"
    private var isConfigured = false
    private var pendingUserId: String? = null
    private var pendingUserIdContext: Context? = null
    @Volatile
    private var attributionInFlight = false

    private val executor = Executors.newSingleThreadExecutor()

    // MARK: - Configuration

    /**
     * Configure Instally with your app credentials.
     * Call once in Application.onCreate() or your main Activity.
     *
     * ```kotlin
     * Instally.configure(this, appId = "app_xxx", apiKey = "key_xxx")
     * ```
     */
    @JvmStatic
    fun configure(context: Context, appId: String, apiKey: String) {
        this.appId = appId
        this.apiKey = apiKey
        this.isConfigured = true
    }

    /**
     * Override the API base URL (for testing/development).
     */
    @JvmStatic
    fun setAPIBase(url: String) {
        this.apiBase = url
    }

    // MARK: - Install Attribution

    /**
     * Track app install attribution. Call once on first app launch, after configure().
     * Automatically reads the Google Play Install Referrer for deterministic matching.
     * Safe to call on every launch — only runs once per install.
     *
     * ```kotlin
     * Instally.trackInstall(this) { result ->
     *     Log.d("Instally", "Matched: ${result.matched}")
     * }
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun trackInstall(context: Context, completion: ((AttributionResult) -> Unit)? = null) {
        if (!isConfigured) {
            Log.e(TAG, "Error: call Instally.configure() before trackInstall()")
            return
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TRACKED, false)) {
            // Already tracked — return cached result
            val cached = AttributionResult(
                matched = prefs.getBoolean(KEY_MATCHED, false),
                attributionId = prefs.getString(KEY_ATTRIBUTION_ID, null),
                confidence = 0.0,
                method = "cached",
                clickId = null
            )
            completion?.invoke(cached)
            flushPendingUserId()
            return
        }

        attributionInFlight = true

        // Try to read the Install Referrer (deterministic attribution), then send
        readInstallReferrer(context) { referrer ->
            val payload = buildPayload(context, referrer)
            sendAttribution(context, payload, completion)
        }
    }

    // MARK: - Purchase Tracking

    /**
     * Track an in-app purchase attributed to the install.
     *
     * ```kotlin
     * Instally.trackPurchase(
     *     context = this,
     *     productId = "premium_monthly",
     *     revenue = 9.99,
     *     currency = "USD",
     *     transactionId = purchase.orderId
     * )
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun trackPurchase(
        context: Context,
        productId: String,
        revenue: Double,
        currency: String = "USD",
        transactionId: String? = null
    ) {
        if (!isConfigured) {
            Log.e(TAG, "Error: call Instally.configure() before trackPurchase()")
            return
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attrId = prefs.getString(KEY_ATTRIBUTION_ID, null)

        if (attrId == null) {
            Log.w(TAG, "No attribution ID found. Install may not have been attributed.")
            return
        }

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val payload = JSONObject().apply {
            put("app_id", appId)
            put("attribution_id", attrId)
            put("product_id", productId)
            put("revenue", revenue)
            put("currency", currency)
            put("timestamp", isoFormat.format(Date()))
            put("sdk_version", SDK_VERSION)
            if (transactionId != null) put("transaction_id", transactionId)
        }

        executor.execute {
            val result = post("/v1/purchases", payload)
            if (result != null) {
                Log.d(TAG, "Purchase tracked: $productId $revenue $currency")
            } else {
                Log.e(TAG, "Purchase tracking failed")
            }
        }
    }

    // MARK: - User ID

    /**
     * Link an external user ID (e.g. RevenueCat appUserID) to this install's attribution.
     * This allows server-side integrations (webhooks) to attribute purchases automatically.
     *
     * ```kotlin
     * Instally.setUserId(this, Purchases.sharedInstance.appUserID)
     * ```
     */
    @JvmStatic
    fun setUserId(context: Context, userId: String) {
        if (!isConfigured) {
            Log.e(TAG, "Error: call Instally.configure() before setUserId()")
            return
        }

        // If attribution is still in flight, queue and send when it completes
        if (attributionInFlight) {
            pendingUserId = userId
            pendingUserIdContext = context.applicationContext
            return
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attrId = prefs.getString(KEY_ATTRIBUTION_ID, null)

        if (attrId == null) {
            // Attribution finished but wasn't matched — queue in case of retry on next launch
            pendingUserId = userId
            pendingUserIdContext = context.applicationContext
            return
        }

        sendUserId(context.applicationContext, userId, attrId)
    }

    private fun sendUserId(context: Context, userId: String, attributionId: String) {
        val payload = JSONObject().apply {
            put("app_id", appId)
            put("attribution_id", attributionId)
            put("user_id", userId)
            put("sdk_version", SDK_VERSION)
        }

        executor.execute {
            val result = post("/v1/user-id", payload)
            if (result != null) {
                Log.d(TAG, "User ID linked: $userId")
            } else {
                Log.e(TAG, "setUserId failed")
            }
        }
    }

    private fun flushPendingUserId() {
        val userId = pendingUserId ?: return
        val context = pendingUserIdContext ?: return
        pendingUserId = null
        pendingUserIdContext = null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attrId = prefs.getString(KEY_ATTRIBUTION_ID, null) ?: return
        sendUserId(context, userId, attrId)
    }

    // MARK: - Public Properties

    /**
     * Check if this install was attributed to a tracking link.
     */
    @JvmStatic
    fun isAttributed(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MATCHED, false)
    }

    /**
     * The cached attribution ID, read without context.
     * Only available after trackInstall() has completed successfully.
     */
    @JvmStatic
    var attributionId: String? = null
        private set

    /**
     * Whether this install was attributed, read without context.
     * Only available after trackInstall() has completed successfully.
     */
    @JvmStatic
    var isAttributed: Boolean = false
        private set

    /**
     * The attribution ID for this install (null if not attributed).
     * Reads from SharedPreferences with the given context.
     */
    @JvmStatic
    fun attributionId(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ATTRIBUTION_ID, null)
    }

    // MARK: - Private

    private fun readInstallReferrer(context: Context, callback: (String?) -> Unit) {
        try {
            val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try {
                            val referrer = client.installReferrer.installReferrer
                            Log.d(TAG, "Install referrer: $referrer")
                            client.endConnection()
                            callback(referrer)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read referrer: ${e.message}")
                            client.endConnection()
                            callback(null)
                        }
                    } else {
                        Log.w(TAG, "Install referrer not available: $responseCode")
                        client.endConnection()
                        callback(null)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.w(TAG, "Install referrer service disconnected")
                    callback(null)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Install referrer client error: ${e.message}")
            callback(null)
        }
    }

    private fun buildPayload(context: Context, referrer: String?): JSONObject {
        val metrics = context.resources.displayMetrics

        return JSONObject().apply {
            put("app_id", appId)
            put("platform", "android")
            put("device_model", Build.MODEL)
            put("os_version", Build.VERSION.RELEASE)
            put("screen_width", metrics.widthPixels)
            put("screen_height", metrics.heightPixels)
            put("timezone", TimeZone.getDefault().id)
            put("language", Locale.getDefault().toLanguageTag())
            put("sdk_version", SDK_VERSION)

            // Install Referrer (deterministic — the key signal for Android)
            if (referrer != null) {
                put("install_referrer", referrer)
                // Extract our click ID if present
                val clickId = parseReferrerParam(referrer, "instally_click_id")
                if (clickId != null) {
                    put("instally_click_id", clickId)
                }
            }
        }
    }

    private fun parseReferrerParam(referrer: String, key: String): String? {
        return referrer.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
    }

    private fun sendAttribution(
        context: Context,
        payload: JSONObject,
        callback: ((AttributionResult) -> Unit)?
    ) {
        executor.execute {
            val json = post("/v1/attribution", payload)
            if (json != null) {
                val result = AttributionResult(
                    matched = json.optBoolean("matched", false),
                    attributionId = json.optString("attribution_id", null),
                    confidence = json.optDouble("confidence", 0.0),
                    method = json.optString("method", "unknown"),
                    clickId = json.optString("click_id", null)
                )

                // Cache result in SharedPreferences
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_TRACKED, true)
                    .putBoolean(KEY_MATCHED, result.matched)
                    .putString(KEY_ATTRIBUTION_ID, result.attributionId)
                    .apply()

                // Update in-memory properties
                this.isAttributed = result.matched
                this.attributionId = result.attributionId

                Log.d(TAG, "Install attribution: matched=${result.matched}, confidence=${result.confidence}, method=${result.method}")
                attributionInFlight = false
                callback?.invoke(result)
                flushPendingUserId()
            } else {
                Log.e(TAG, "Attribution request failed")
                attributionInFlight = false
                // Don't mark as tracked so it retries next launch
                callback?.invoke(
                    AttributionResult(false, null, 0.0, "error", null)
                )
            }
        }
    }

    private fun post(endpoint: String, payload: JSONObject): JSONObject? {
        return try {
            val url = URL(apiBase + endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.setRequestProperty("X-App-ID", appId)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()
                JSONObject(body)
            } else {
                Log.e(TAG, "API error: ${conn.responseCode}")
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            null
        }
    }
}
