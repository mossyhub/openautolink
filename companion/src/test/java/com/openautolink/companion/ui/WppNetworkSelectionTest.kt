package com.openautolink.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WppNetworkSelectionTest {
    @Test
    fun duplicateBssidsAreCollapsedIndependentlyInEachBand() {
        val five = WppVisibleNetwork("Car|5ghz", "aa:00:00:00:00:01", -65, 5180)
        val two = WppVisibleNetwork("Car|5ghz", "aa:00:00:00:00:02", -25, 2412)
        val scans = listOf(two.copy(rssiDbm = -80), five, two, five.copy(rssiDbm = -90), five)

        // Keep both bands, prefer 5 GHz in the picker, and pick each band's best RSSI.
        assertEquals(listOf(five, two), selectWppNetworks(scans))
        assertEquals(listOf(five, two), selectWppNetworks(scans.reversed()))
    }

    @Test
    fun equalRssiUsesLexicallyFirstBssidRegardlessOfScanOrder() {
        val first = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -40, 5180)
        val second = first.copy(bssid = "aa:00:00:00:00:02")

        assertEquals(listOf(first), selectWppNetworks(listOf(second, first)))
        assertEquals(listOf(first), selectWppNetworks(listOf(first, second)))
    }

    @Test
    fun emptyScansAndHiddenOrMissingIdentifiersProduceNoCandidates() {
        assertEquals(emptyList<WppVisibleNetwork>(), selectWppNetworks(emptyList()))
        assertEquals(emptyList<WppVisibleNetwork>(), selectWppNetworks(listOf(
            WppVisibleNetwork("", "aa:00:00:00:00:01", -40, 5180),
            WppVisibleNetwork("Car", "", -40, 5180),
            WppVisibleNetwork("Car", " ", -40, 5180),
        )))
    }

    @Test
    fun labelsRetainExistingBandAndChannelMapping() {
        val base = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -40, 5180)
        listOf(
            Triple(2412, "2.4 GHz", "1"),
            Triple(2472, "2.4 GHz", "13"),
            Triple(2484, "2.4 GHz", "14"),
            Triple(5180, "5 GHz", "36"),
            Triple(5745, "5 GHz", "149"),
            Triple(6000, "6000 MHz", "?"),
            Triple(0, "0 MHz", "-"),
        ).forEach { (frequency, band, channel) ->
            val network = base.copy(frequencyMhz = frequency)
            assertEquals(band, network.bandLabel)
            assertEquals(channel, network.channelLabel)
        }
    }

    @Test
    fun caseDistinctSsidsHaveDeterministicDisplayOrderOnTies() {
        val upper = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -40, 5180)
        val lower = upper.copy(ssid = "car")

        assertEquals(listOf(upper, lower), selectWppNetworks(listOf(lower, upper)))
        assertEquals(listOf(upper, lower), selectWppNetworks(listOf(upper, lower)))
    }

    @Test
    fun equalRssiAndBssidUseFrequencyToBreakTies() {
        val lowerChannel = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -40, 5180)
        val higherChannel = lowerChannel.copy(frequencyMhz = 5200)

        assertEquals(listOf(lowerChannel), selectWppNetworks(listOf(higherChannel, lowerChannel)))
        assertEquals(listOf(lowerChannel), selectWppNetworks(listOf(lowerChannel, higherChannel)))
    }

    @Test
    fun whitespaceIsPartOfSsidIdentityIncludingAllSpaceSsid() {
        val names = listOf("Car", " Car", "Car ", " Car ", " ")
        val scans = names.mapIndexed { index, ssid ->
            WppVisibleNetwork(ssid, "aa:00:00:00:00:0$index", -40 - index, 5180)
        }

        assertEquals(scans, selectWppNetworks(scans.reversed()))
    }

    @Test
    fun caseDistinctSsidsRemainSeparateNetworks() {
        val upper = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -40, 5180)
        val lower = WppVisibleNetwork("car", "aa:00:00:00:00:02", -50, 5180)

        assertEquals(listOf(upper, lower), selectWppNetworks(listOf(lower, upper)))
    }

    @Test
    fun strongestBssidWinsWithinSameSsidAndBand() {
        val weak = WppVisibleNetwork("Car", "aa:00:00:00:00:01", -85, 5180)
        val strong = WppVisibleNetwork("Car", "aa:00:00:00:00:02", -35, 5200)

        assertEquals(listOf(strong), selectWppNetworks(listOf(weak, strong)))
        assertEquals(listOf(strong), selectWppNetworks(listOf(strong, weak)))
    }
}
