package com.deepmost.rabbitav

import com.deepmost.rabbitav.core.data.db.HazardEventDao
import com.deepmost.rabbitav.core.data.db.HazardEventEntity
import com.deepmost.rabbitav.core.data.db.HazardSiteDao
import com.deepmost.rabbitav.core.data.db.HazardSiteEntity
import com.deepmost.rabbitav.core.data.repo.HazardRepository
import com.deepmost.rabbitav.core.hazard.Geohash
import com.deepmost.rabbitav.core.hazard.HazardSource
import com.deepmost.rabbitav.core.hazard.NewHazardEvent
import com.deepmost.rabbitav.core.imu.HazardType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 gate: dedup/clustering + confidence accumulation + decay. */
class HazardStoreTest {

    // ------- in-memory fakes over the DAO interfaces -------
    private class FakeSiteDao : HazardSiteDao {
        val rows = mutableListOf<HazardSiteEntity>()
        var nextId = 1L
        val flow = MutableStateFlow<List<HazardSiteEntity>>(emptyList())

        override suspend fun insert(site: HazardSiteEntity): Long {
            val id = nextId++
            rows.add(site.copy(id = id))
            flow.value = rows.toList()
            return id
        }

        override suspend fun update(site: HazardSiteEntity) {
            val i = rows.indexOfFirst { it.id == site.id }
            if (i >= 0) rows[i] = site
            flow.value = rows.toList()
        }

        override suspend fun byGeohashes(hashes: List<String>) = rows.filter { it.geohash7 in hashes }
        override suspend fun all() = rows.toList()
        override fun allFlow(): Flow<List<HazardSiteEntity>> = flow
        override suspend fun delete(ids: List<Long>) {
            rows.removeAll { it.id in ids }
            flow.value = rows.toList()
        }

        override suspend fun count() = rows.size
    }

    private class FakeEventDao : HazardEventDao {
        val rows = mutableListOf<HazardEventEntity>()
        override suspend fun insert(event: HazardEventEntity): Long {
            rows.add(event.copy(id = rows.size + 1L))
            return rows.size.toLong()
        }

        override suspend fun forTrip(tripId: Long) = rows.filter { it.tripId == tripId }
        override suspend fun unsynced(limit: Int) = rows.filter { !it.synced }.take(limit)
        override suspend fun markSynced(ids: List<Long>) {
            for (i in rows.indices) if (rows[i].id in ids) rows[i] = rows[i].copy(synced = true)
        }

        override suspend fun unsyncedCount() = rows.count { !it.synced }
    }

    private fun event(
        lat: Double, lon: Double, heading: Float = 90f,
        type: HazardType = HazardType.POTHOLE, conf: Float = 0.6f, timeMs: Long = 1_000_000L,
    ) = NewHazardEvent(type, conf, lat, lon, heading, 8f, HazardSource.IMU, timeMs, tripId = 1)

    @Test
    fun nearbySameTypeEventsCluster() = runBlocking {
        val sites = FakeSiteDao()
        val repo = HazardRepository(sites, FakeEventDao())

        repo.recordEvent(event(12.97160, 77.59460))
        // ~8 m east (1e-4 deg lon at this latitude ~ 10.9 m; use 0.00007 ~ 7.6m)
        repo.recordEvent(event(12.97160, 77.59467))

        assertEquals(1, sites.rows.size)
        val site = sites.rows.single()
        assertEquals(2, site.hitCount)
        // conf = 1 - (1-0.6)(1-0.6) = 0.84
        assertEquals(0.84f, site.confidence, 0.01f)
    }

    @Test
    fun farEventsMakeSeparateSites() = runBlocking {
        val sites = FakeSiteDao()
        val repo = HazardRepository(sites, FakeEventDao())
        repo.recordEvent(event(12.97160, 77.59460))
        repo.recordEvent(event(12.97160, 77.59500)) // ~43 m east
        assertEquals(2, sites.rows.size)
    }

    @Test
    fun oppositeHeadingsStaySeparate() = runBlocking {
        val sites = FakeSiteDao()
        val repo = HazardRepository(sites, FakeEventDao())
        repo.recordEvent(event(12.97160, 77.59460, heading = 90f))
        repo.recordEvent(event(12.97160, 77.59461, heading = 270f))
        assertEquals(2, sites.rows.size)
    }

    @Test
    fun differentTypesStaySeparate() = runBlocking {
        val sites = FakeSiteDao()
        val repo = HazardRepository(sites, FakeEventDao())
        repo.recordEvent(event(12.97160, 77.59460, type = HazardType.POTHOLE))
        repo.recordEvent(event(12.97160, 77.59461, type = HazardType.SPEED_BREAKER))
        assertEquals(2, sites.rows.size)
    }

    @Test
    fun decayHalvesInThirtyDays() {
        val thirtyDays = 30L * 24 * 3600 * 1000
        val decayed = HazardRepository.decayedConfidence(0.8f, 0L, thirtyDays)
        assertEquals(0.4f, decayed, 0.01f)
    }

    @Test
    fun sitesNearFiltersDecayedBelowFloor() = runBlocking {
        val sites = FakeSiteDao()
        val repo = HazardRepository(sites, FakeEventDao())
        val old = System.currentTimeMillis() - 100L * 24 * 3600 * 1000 // 100 days: 0.6 -> ~0.06
        repo.recordEvent(event(12.97160, 77.59460, timeMs = old))
        val near = repo.sitesNear(12.97160, 77.59460, 100.0)
        assertTrue(near.isEmpty())
        assertEquals(1, repo.pruneDecayed())
    }

    @Test
    fun geohashKnownVector() {
        // classic vector: (57.64911, 10.40744) -> u4pruydqqvj
        assertEquals("u4pruyd", Geohash.encode(57.64911, 10.40744, 7))
        val neighbors = Geohash.withNeighbors("u4pruyd")
        assertTrue("u4pruyd" in neighbors)
        assertEquals(9, neighbors.size)
    }
}
