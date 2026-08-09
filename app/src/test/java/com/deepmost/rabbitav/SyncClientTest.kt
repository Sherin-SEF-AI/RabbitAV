package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.data.sync.SyncClient
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M4 gate: sync request construction verified with MockWebServer. */
class SyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SyncClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun batch() = SyncClient.EventBatch(
        deviceId = "test-device-uuid",
        events = listOf(
            SyncClient.HazardEventDto("POTHOLE", 12.9716, 77.5946, 90f, 8.3f, 0.7f, "imu", 1723180000000),
            SyncClient.HazardEventDto("SPEED_BREAKER", 12.9720, 77.5950, 91f, 6.1f, 0.84f, "fused", 1723180050000),
        ),
    )

    @Test
    fun postEventsGzipsAndParses() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":2}"""))

        val result = client.postEvents(batch())
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/hazard-events", recorded.path)
        assertEquals("gzip", recorded.getHeader("Content-Encoding"))

        // body must gunzip back into the JSON batch
        val json = GZIPInputStream(recorded.body.inputStream()).bufferedReader().readText()
        assertTrue(json.contains(""""deviceId":"test-device-uuid""""))
        assertTrue(json.contains(""""type":"POTHOLE""""))
        assertTrue(json.contains(""""source":"imu""""))
        assertEquals(2, Regex("\"lat\":").findAll(json).count())
    }

    @Test
    fun postEventsFailsOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.postEvents(batch()).isFailure)
    }

    @Test
    fun tileFetchParsesSites() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sites":[{"type":"POTHOLE","lat":12.97,"lon":77.59,"heading":45.0,"confidence":0.66,"hits":4,"lastSeen":1723000000000}]}"""
            )
        )
        val result = client.getTile("tdr1v")
        assertTrue(result.isSuccess)
        val sites = result.getOrThrow()
        assertEquals(1, sites.size)
        assertEquals("POTHOLE", sites[0].type)
        assertEquals(0.66f, sites[0].confidence, 1e-4f)

        assertEquals("/v1/hazard-tiles/tdr1v", server.takeRequest().path)
    }

    @Test
    fun emptyBaseUrlDisablesClient() {
        val disabled = SyncClient(OkHttpClient(), "")
        assertTrue(disabled.postEvents(batch()).isFailure)
        assertTrue(!disabled.enabled)
    }
}
