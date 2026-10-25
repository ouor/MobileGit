package com.ouor.mobilegit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueKeysTest {
    @Test fun sameMillisecondRequestsGetDistinctKeys() {
        // what a browser does when it opens the web page: several requests, same ms, same IP, same status
        val rows = List(3) { Triple(1_761_380_000_000L, "192.168.31.5", 200) } + Triple(1_761_380_000_001L, "192.168.31.5", 200)
        val keys = uniqueKeys(rows) { "${it.first}-${it.second}-${it.third}" }
        assertEquals(rows.size, keys.toSet().size)
        assertEquals("1761380000000-192.168.31.5-200", keys[0]) // the first keeps its plain key
    }
}
