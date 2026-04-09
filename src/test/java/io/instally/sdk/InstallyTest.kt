package io.instally.sdk

import android.content.Context
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class InstallyTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        server = MockWebServer()
        server.start()
        resetInstally()
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Clear SharedPreferences
        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetInstally()
    }

    /**
     * Reset all private state in the Instally singleton via reflection.
     */
    private fun resetInstally() {
        setPrivateField("appId", null)
        setPrivateField("apiKey", null)
        setPrivateField("isConfigured", false)
        setPrivateField("pendingUserId", null)
        setPrivateField("pendingUserIdContext", null)
        setPrivateField("attributionInFlight", false)
        setPrivateField("apiBase", "https://us-central1-instally-5f6fd.cloudfunctions.net/api")
        // Reset public properties
        Instally.javaClass.getDeclaredField("attributionId").apply {
            isAccessible = true
            set(Instally, null)
        }
        Instally.javaClass.getDeclaredField("isAttributed").apply {
            isAccessible = true
            // isAttributed is a Kotlin property backed by a field
            set(Instally, false)
        }
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = Instally.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(Instally, value)
    }

    private fun getPrivateField(name: String): Any? {
        val field = Instally.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(Instally)
    }

    private fun configureWithMockServer() {
        Instally.configure(context, appId = "test_app", apiKey = "test_key")
        Instally.setAPIBase(server.url("/api").toString().removeSuffix("/"))
    }

    /**
     * Call trackInstall with the install referrer skipped (will fail/timeout immediately
     * in Robolectric), so we just test the HTTP path.
     */
    private fun trackInstallAndWait(completion: ((AttributionResult) -> Unit)? = null) {
        val latch = CountDownLatch(1)
        var result: AttributionResult? = null
        Instally.trackInstall(context) { r ->
            result = r
            completion?.invoke(r)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    // -------------------------------------------------------
    // 1. configure() sets credentials
    // -------------------------------------------------------

    @Test
    fun `configure sets appId apiKey and isConfigured`() {
        Instally.configure(context, appId = "my_app", apiKey = "my_key")
        assertEquals("my_app", getPrivateField("appId"))
        assertEquals("my_key", getPrivateField("apiKey"))
        assertEquals(true, getPrivateField("isConfigured"))
    }

    // -------------------------------------------------------
    // 2. trackInstall() without configure() logs error and returns
    // -------------------------------------------------------

    @Test
    fun `trackInstall without configure returns immediately`() {
        // Should not throw; should simply return without crashing
        var callbackInvoked = false
        Instally.trackInstall(context) { callbackInvoked = true }
        // Give a moment for any async work
        Thread.sleep(200)
        assertFalse("Callback should not be invoked when not configured", callbackInvoked)
    }

    // -------------------------------------------------------
    // 3. trackPurchase() without configure() logs error and returns
    // -------------------------------------------------------

    @Test
    fun `trackPurchase without configure returns immediately`() {
        // Should not throw
        Instally.trackPurchase(
            context = context,
            productId = "premium",
            revenue = 9.99,
            currency = "USD",
            transactionId = "txn_123"
        )
        // If we got here without exception, the guard worked
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------
    // 4. setUserId() without configure() logs error and returns
    // -------------------------------------------------------

    @Test
    fun `setUserId without configure returns immediately`() {
        Instally.setUserId(context, "user_123")
        // Should not throw, and no network call
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------
    // 5. trackInstall() sends correct payload
    // -------------------------------------------------------

    @Test
    fun `trackInstall sends correct payload fields`() {
        configureWithMockServer()

        val responseBody = JSONObject().apply {
            put("matched", true)
            put("attribution_id", "attr_abc")
            put("confidence", 0.95)
            put("method", "referrer")
            put("click_id", "click_xyz")
        }
        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))

        trackInstallAndWait()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Expected a request to be sent", request)
        assertTrue(request!!.path!!.contains("/v1/attribution"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("test_app", body.getString("app_id"))
        assertEquals("android", body.getString("platform"))
        assertTrue(body.has("device_model"))
        assertTrue(body.has("os_version"))
        assertEquals("1.0.0", body.getString("sdk_version"))
        assertTrue(body.has("screen_width"))
        assertTrue(body.has("screen_height"))
        assertTrue(body.has("timezone"))
        assertTrue(body.has("language"))
    }

    // -------------------------------------------------------
    // 6. trackInstall() returns cached on second call
    // -------------------------------------------------------

    @Test
    fun `trackInstall returns cached result on second call`() {
        configureWithMockServer()

        val responseBody = JSONObject().apply {
            put("matched", true)
            put("attribution_id", "attr_abc")
            put("confidence", 0.95)
            put("method", "referrer")
            put("click_id", "click_xyz")
        }
        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))

        // First call
        trackInstallAndWait()

        // Second call — should use cache and NOT make another network request
        val latch = CountDownLatch(1)
        var cachedResult: AttributionResult? = null
        Instally.trackInstall(context) { r ->
            cachedResult = r
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)

        assertNotNull(cachedResult)
        assertEquals("cached", cachedResult!!.method)
        assertEquals(true, cachedResult!!.matched)
        assertEquals("attr_abc", cachedResult!!.attributionId)
        // Only 1 request should have been made
        assertEquals(1, server.requestCount)
    }

    // -------------------------------------------------------
    // 7. trackInstall() network failure doesn't mark as tracked
    // -------------------------------------------------------

    @Test
    fun `trackInstall network failure does not mark as tracked`() {
        configureWithMockServer()

        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val latch = CountDownLatch(1)
        var result: AttributionResult? = null
        Instally.trackInstall(context) { r ->
            result = r
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)

        assertNotNull(result)
        assertEquals(false, result!!.matched)
        assertEquals("error", result!!.method)

        // SharedPreferences should NOT have install_tracked = true
        val prefs = context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("install_tracked", false))
    }

    // -------------------------------------------------------
    // 8. trackPurchase() without attribution ID returns early
    // -------------------------------------------------------

    @Test
    fun `trackPurchase without attribution ID returns early`() {
        configureWithMockServer()

        // No trackInstall() called, so no attribution_id in prefs
        Instally.trackPurchase(
            context = context,
            productId = "premium",
            revenue = 9.99
        )
        Thread.sleep(500)
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------
    // 9. trackPurchase() sends correct payload
    // -------------------------------------------------------

    @Test
    fun `trackPurchase sends correct payload`() {
        configureWithMockServer()

        // Set up attribution ID in prefs (simulate a previous successful trackInstall)
        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("install_tracked", true)
            .putBoolean("matched", true)
            .putString("attribution_id", "attr_abc")
            .commit()

        // Enqueue response for trackPurchase
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        Instally.trackPurchase(
            context = context,
            productId = "premium_monthly",
            revenue = 9.99,
            currency = "EUR",
            transactionId = "txn_456"
        )

        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull("Expected a purchase request", request)
        assertTrue(request!!.path!!.contains("/v1/purchases"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("test_app", body.getString("app_id"))
        assertEquals("attr_abc", body.getString("attribution_id"))
        assertEquals("premium_monthly", body.getString("product_id"))
        assertEquals(9.99, body.getDouble("revenue"), 0.001)
        assertEquals("EUR", body.getString("currency"))
        assertEquals("txn_456", body.getString("transaction_id"))
        assertEquals("1.0.0", body.getString("sdk_version"))
        assertTrue(body.has("timestamp"))
    }

    // -------------------------------------------------------
    // 10. setUserId() queues when attribution in flight
    // -------------------------------------------------------

    @Test
    fun `setUserId queues when attribution in flight`() {
        configureWithMockServer()

        // Simulate attribution in flight
        setPrivateField("attributionInFlight", true)

        Instally.setUserId(context, "user_queued")

        // Should be stored as pending
        assertEquals("user_queued", getPrivateField("pendingUserId"))
        // No request should have been made yet
        assertEquals(0, server.requestCount)
    }

    // -------------------------------------------------------
    // 11. setUserId() sends correct payload
    // -------------------------------------------------------

    @Test
    fun `setUserId sends correct payload`() {
        configureWithMockServer()

        // Set up attribution ID in prefs
        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("attribution_id", "attr_abc")
            .commit()

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        Instally.setUserId(context, "rc_user_789")

        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull("Expected a user-id request", request)
        assertTrue(request!!.path!!.contains("/v1/user-id"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("test_app", body.getString("app_id"))
        assertEquals("attr_abc", body.getString("attribution_id"))
        assertEquals("rc_user_789", body.getString("user_id"))
        assertEquals("1.0.0", body.getString("sdk_version"))
    }

    // -------------------------------------------------------
    // 12. All requests include X-API-Key and X-App-ID headers
    // -------------------------------------------------------

    @Test
    fun `requests include X-API-Key and X-App-ID headers`() {
        configureWithMockServer()

        // Set up attribution ID for a purchase request (easier to verify headers)
        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("attribution_id", "attr_abc")
            .commit()

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        Instally.trackPurchase(context, productId = "p", revenue = 1.0)

        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("test_key", request!!.getHeader("X-API-Key"))
        assertEquals("test_app", request.getHeader("X-App-ID"))
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `attribution request includes X-API-Key and X-App-ID headers`() {
        configureWithMockServer()

        val responseBody = JSONObject().apply {
            put("matched", false)
            put("attribution_id", JSONObject.NULL)
            put("confidence", 0.0)
            put("method", "none")
        }
        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))

        trackInstallAndWait()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("test_key", request!!.getHeader("X-API-Key"))
        assertEquals("test_app", request.getHeader("X-App-ID"))
    }

    // -------------------------------------------------------
    // 13. Install Referrer params are parsed correctly
    // -------------------------------------------------------

    @Test
    fun `parseReferrerParam extracts instally_click_id from referrer string`() {
        val method = Instally.javaClass.getDeclaredMethod(
            "parseReferrerParam", String::class.java, String::class.java
        )
        method.isAccessible = true

        // Standard referrer with instally_click_id
        val result1 = method.invoke(Instally, "utm_source=google&instally_click_id=abc123&utm_medium=cpc", "instally_click_id")
        assertEquals("abc123", result1)

        // instally_click_id at the start
        val result2 = method.invoke(Instally, "instally_click_id=xyz789&utm_source=google", "instally_click_id")
        assertEquals("xyz789", result2)

        // instally_click_id at the end
        val result3 = method.invoke(Instally, "utm_source=google&instally_click_id=end123", "instally_click_id")
        assertEquals("end123", result3)

        // No instally_click_id present
        val result4 = method.invoke(Instally, "utm_source=google&utm_medium=cpc", "instally_click_id")
        assertNull(result4)

        // Empty referrer string
        val result5 = method.invoke(Instally, "", "instally_click_id")
        assertNull(result5)

        // instally_click_id with empty value
        val result6 = method.invoke(Instally, "instally_click_id=&utm_source=google", "instally_click_id")
        assertEquals("", result6)
    }

    @Test
    fun `buildPayload includes instally_click_id from referrer`() {
        configureWithMockServer()

        val method = Instally.javaClass.getDeclaredMethod(
            "buildPayload", Context::class.java, String::class.java
        )
        method.isAccessible = true

        val payload = method.invoke(Instally, context, "utm_source=test&instally_click_id=click_abc") as JSONObject
        assertEquals("click_abc", payload.getString("instally_click_id"))
        assertEquals("utm_source=test&instally_click_id=click_abc", payload.getString("install_referrer"))
    }

    @Test
    fun `buildPayload without referrer omits instally_click_id and install_referrer`() {
        configureWithMockServer()

        val method = Instally.javaClass.getDeclaredMethod(
            "buildPayload", Context::class.java, String::class.java
        )
        method.isAccessible = true

        val payload = method.invoke(Instally, context, null as String?) as JSONObject
        assertFalse(payload.has("instally_click_id"))
        assertFalse(payload.has("install_referrer"))
    }

    // -------------------------------------------------------
    // Additional edge cases
    // -------------------------------------------------------

    @Test
    fun `trackInstall updates in-memory attributionId and isAttributed`() {
        configureWithMockServer()

        val responseBody = JSONObject().apply {
            put("matched", true)
            put("attribution_id", "attr_mem")
            put("confidence", 1.0)
            put("method", "referrer")
            put("click_id", "click_mem")
        }
        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))

        trackInstallAndWait()

        assertEquals("attr_mem", Instally.attributionId)
        assertTrue(Instally.isAttributed)
    }

    @Test
    fun `isAttributed context reads from SharedPreferences`() {
        assertFalse(Instally.isAttributed(context))

        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("matched", true)
            .commit()

        assertTrue(Instally.isAttributed(context))
    }

    @Test
    fun `attributionId context reads from SharedPreferences`() {
        assertNull(Instally.attributionId(context))

        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("attribution_id", "attr_prefs")
            .commit()

        assertEquals("attr_prefs", Instally.attributionId(context))
    }

    @Test
    fun `setUserId flushes pending after trackInstall completes`() {
        configureWithMockServer()

        val responseBody = JSONObject().apply {
            put("matched", true)
            put("attribution_id", "attr_flush")
            put("confidence", 1.0)
            put("method", "referrer")
        }
        // First response for attribution, second for user-id
        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        // Set userId before trackInstall — it will be queued since attribution is about to be in flight
        // We need to set it during the in-flight window
        setPrivateField("attributionInFlight", true)
        Instally.setUserId(context, "pending_user")
        setPrivateField("attributionInFlight", false)

        // Now trackInstall — the SDK sets attributionInFlight=true, then when done calls flushPendingUserId
        // But we already have a pending user from above. Let's just directly call trackInstall.
        // Reset the pending state properly
        resetInstally()
        configureWithMockServer()

        server.enqueue(MockResponse().setBody(responseBody.toString()).setResponseCode(200))
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        // Queue the userId before trackInstall finishes
        setPrivateField("pendingUserId", "pending_user")
        setPrivateField("pendingUserIdContext", context)

        trackInstallAndWait()

        // Wait a bit for the pending userId flush
        Thread.sleep(1000)

        // Should have 2 requests: attribution + user-id
        assertTrue("Expected at least 2 requests", server.requestCount >= 2)
    }

    @Test
    fun `trackPurchase default currency is USD`() {
        configureWithMockServer()

        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("attribution_id", "attr_usd")
            .commit()

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        // Call without explicit currency
        Instally.trackPurchase(context, productId = "basic", revenue = 4.99)

        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = JSONObject(request!!.body.readUtf8())
        assertEquals("USD", body.getString("currency"))
    }

    @Test
    fun `trackPurchase omits transaction_id when null`() {
        configureWithMockServer()

        context.getSharedPreferences("instally_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("attribution_id", "attr_no_txn")
            .commit()

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        Instally.trackPurchase(context, productId = "basic", revenue = 4.99, transactionId = null)

        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = JSONObject(request!!.body.readUtf8())
        assertFalse(body.has("transaction_id"))
    }
}
