package com.ncm.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestPolicyTest {

    @Test
    fun typingSeveralIntermediateQueriesProducesNoOnlineRequests() {
        val onlineRequestCount = buildList {
            repeat(5) {
                if (searchRequestPlan(committed = false, sourceReady = true).searchOnline) add(Unit)
            }
            if (searchRequestPlan(committed = true, sourceReady = true).searchOnline) add(Unit)
        }.size

        assertEquals(1, onlineRequestCount)
    }

    @Test
    fun explicitSubmissionRequiresAReadySource() {
        assertFalse(searchRequestPlan(committed = true, sourceReady = false).searchOnline)
        assertTrue(searchRequestPlan(committed = true, sourceReady = true).searchOnline)
    }
}
