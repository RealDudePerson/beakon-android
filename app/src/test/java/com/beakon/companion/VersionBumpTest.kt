package com.beakon.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/** B-4: an update is being published, so the version must move. */
class VersionBumpTest {

    @Test
    fun `the version code was bumped for the update`() {
        assertEquals(3, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `the version name was bumped for the update`() {
        assertEquals("1.1", BuildConfig.VERSION_NAME)
    }
}
