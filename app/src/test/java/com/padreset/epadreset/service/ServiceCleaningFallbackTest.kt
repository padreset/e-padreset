package com.padreset.epadreset.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceCleaningFallbackTest {
    @Test
    fun `an intensity cleaning the printer refuses falls back to all nozzles without a power clean`() {
        assertEquals(CleaningFallback(groupIndex = 0, powerClean = false), serviceCleaningFallback("Gentle % Cleaning"))
        assertEquals(CleaningFallback(0, false), serviceCleaningFallback("Normal % Cleaning"))
        assertEquals(CleaningFallback(0, false), serviceCleaningFallback("Strong % Cleaning"))
    }

    @Test
    fun `a named color group still falls back to that group`() {
        assertEquals(CleaningFallback(1, false), serviceCleaningFallback("BLK % Gentle"))
        assertEquals(CleaningFallback(2, false), serviceCleaningFallback("YMC % Normal"))
    }

    @Test
    fun `charging is not a head clean`() {
        assertNull(serviceCleaningFallback("Normal % Charging"))
    }
}
