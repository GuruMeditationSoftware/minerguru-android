package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PoolSlotTests {
    @Before fun germanUi() = useGermanTexts()

    private fun slotsForm() = MinerSettingsForm.from(Fixtures.device(Fixtures.axeSlotsInfo()))
    private val ocean = PoolConfig.create("OCEAN", "mine.ocean.xyz", 3334, "bc1qocean.rig")
    private val atlas = PoolConfig.create("Atlas", "solo.atlaspool.io", 3333, "bc1qatlas.rig")

    @Test fun decodesSlotsAndSkipsMalformedEntries() {
        val info = Fixtures.axeSlotsInfo()
        assertEquals(listOf(0, 1, 4), info.axePools?.map { it.id })
        assertEquals(0, info.primaryPoolIndex)
        assertEquals(1, info.secondaryPoolIndex)
        assertEquals(1, info.axePools!![2].advanced["stratumTLS"])
    }

    @Test fun olderFirmwareHasNoSlots() {
        assertNull(Fixtures.bitaxeInfo().axePools)
        val form = MinerSettingsForm.from(Fixtures.device(Fixtures.bitaxeInfo()))
        assertEquals(MinerSettingsForm.PoolApi.LEGACY, form.poolApi)
        assertEquals(2, form.slots.size)
    }

    @Test fun formLoadsEightSlots() {
        val form = slotsForm()
        assertEquals(MinerSettingsForm.PoolApi.SLOTS, form.poolApi)
        assertEquals(AxeOSPool.SLOT_COUNT, form.slots.size)
        assertEquals("eusolo.ckpool.org", form.primary.host)
        assertEquals("public-pool.io", form.fallback.host)
        assertEquals("mine.ocean.xyz", form.slots[4].host)
        assertEquals(setOf(0, 1, 4), form.storedSlots)
        assertEquals(listOf(0, 1, 2, 4), form.visibleSlots)
    }

    @Test fun slotPayloadKeepsAdvancedSettingsAndMaskedPassword() {
        val form = slotsForm().let { it.withSlot(4, it.slots[4].copy(user = "bc1qocean.rig2")) }
        val json = form.poolPayload(false).toJson()
        assertEquals(listOf("pools", "primaryPoolIndex", "secondaryPoolIndex"), json.keys().asSequence().toList().sorted())
        val pools = json.getJSONArray("pools")
        assertEquals(3, pools.length())
        val oceanJson = pools.getJSONObject(2)
        assertEquals(4, oceanJson.getInt("id"))
        assertEquals("bc1qocean.rig2", oceanJson.getString("stratumUser"))
        assertEquals(1, oceanJson.getInt("stratumTLS"))
        assertEquals("SV2", oceanJson.getString("stratumProtocol"))
        assertEquals(AxeOSPool.MASKED_PASSWORD, oceanJson.getString("stratumPassword"))
    }

    @Test fun clearedReserveSlotIsDeletedButNotFallback() {
        var form = slotsForm().clearSlot(4)
        assertEquals(listOf(4), form.slotsToClear)
        assertEquals(listOf(0, 1), form.poolPayload(false).pools?.map { it.id })
        form = form.clearSlot(1)
        assertEquals(listOf(4), form.slotsToClear)
        assertNotNull(form.poolValidationMessage)
    }

    @Test fun incompleteSlotBlocksSaving() {
        val form = slotsForm()
        assertNull(form.poolValidationMessage)
        val broken = form.withSlot(2, form.slots[2].copy(host = "solo.atlaspool.io"))
        assertTrue(broken.poolValidationMessage!!.contains("Slot 3"))
    }

    @Test fun slotFirmwareHasNoDualPool() = assertFalse(Fixtures.device(Fixtures.axeSlotsInfo()).supportsDualPool)

    @Test fun selectsExistingSlotAsPrimary() {
        val form = slotsForm().makePrimary(ocean)
        assertEquals(4, form.primarySlot)
        assertEquals(1, form.slots[4].advanced?.advanced?.get("stratumTLS"))
        assertEquals("eusolo.ckpool.org", form.slots[0].host)
    }

    @Test fun newPoolGoesIntoFreeSlot() {
        val form = slotsForm().makePrimary(atlas)
        assertEquals(2, form.primarySlot)
        assertEquals("solo.atlaspool.io", form.slots[2].host)
        assertNull(form.slots[2].advanced)
        assertEquals("eusolo.ckpool.org", form.slots[0].host)
    }

    @Test fun makingFallbackPrimarySwapsRoles() {
        val form = slotsForm().makePrimary(PoolConfig.create("Public", "public-pool.io", 21496, "bc1qexamplewallet.bitaxe"))
        assertEquals(1, form.primarySlot)
        assertEquals(0, form.secondarySlot)
    }

    @Test fun legacyFirmwareOverwritesSlots() {
        val form = MinerSettingsForm.from(Fixtures.device(Fixtures.dualPoolInfo())).makePrimary(ocean).makeFallback(atlas)
        val p = form.poolPayload(true)
        assertEquals("mine.ocean.xyz", p.stratumURL)
        assertEquals(3334, p.stratumPort)
        assertEquals("solo.atlaspool.io", p.fallbackStratumURL)
        assertEquals(1, p.poolMode)
        assertEquals(1, p.poolBalance)
        assertNull(p.pools)
    }

    @Test fun unknownLegacyPasswordIsNotSent() {
        val form = MinerSettingsForm.from(Fixtures.device(Fixtures.dualPoolInfo()))
        assertNull(form.poolPayload(true).stratumPassword)
        assertNull(form.poolPayload(true).fallbackStratumPassword)
        val withSecret = form.assign(PoolConfig.create("OCEAN", "mine.ocean.xyz", 3334, "bc1q.rig", "secret"), 0)
        assertEquals("secret", withSecret.poolPayload(true).stratumPassword)
    }

    @Test fun libraryEntriesFromMiner() {
        val endpoint = slotsForm().primary
        val config = PoolConfig.fromEndpoint(PoolConfig.suggestedName(endpoint.host, endpoint.port, endpoint.user), endpoint)
        assertEquals("CKPool EU (Solo) · bitaxe", config.name)
        assertEquals("x", config.password) // masked password is not stored
        assertEquals("mine.ocean.xyz", PoolConfig.create("O", "stratum+tcp://mine.ocean.xyz:3334").host)
        assertTrue(config.matches(endpoint))
    }

    @Test fun privacyScramblingKeepsShapeAndWorker() {
        val s = Privacy.scramble("192.168.1.28")
        assertEquals(12, s.length)
        assertEquals(s, Privacy.scramble("192.168.1.28"))
        assertNotEquals("192.168.1.28", s)
        assertTrue(Privacy.scrambleStratumUser("bc1qwallet.rig1").endsWith(".rig1"))
        assertFalse(Privacy.scrambleFreeText("connect to 10.0.0.5 ok").contains("10.0.0.5"))
    }
}
