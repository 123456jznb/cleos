package com.cleo.cleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which address the 「去蓝奏云看新版」 button opens. */
class ReleasesTest {
    @Test
    fun theFirstDomainThatStillResolvesIsOpened() {
        val gone = setOf(Releases.HOSTS[0])
        assertEquals("https://${Releases.HOSTS[1]}/b01gicbubg", Releases.pick { it !in gone })
    }

    @Test
    fun withNoneResolvingTheFirstIsOpenedAnyway() {
        assertEquals(Releases.url(Releases.HOSTS.first()), Releases.pick { false })
    }

    @Test
    fun theRetiredDomainIsNotAmongThem() {
        assertTrue(Releases.HOSTS.none { it.endsWith("lanzouc.com") })
    }
}
