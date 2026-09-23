package com.cleo.cleos.data

import com.cleo.cleos.ai.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSettingsTest {
    @Test
    fun choicesSurviveAndGroupsAddedLaterGetTheirDefault() {
        val chosen = setOf(ToolGroup.Todos, ToolGroup.Diary)
        assertEquals(chosen, decodeTools(encodeTools(chosen)))
        // Written before Secrets existed: it was never chosen, so it takes its default (on).
        assertEquals(chosen + ToolGroup.Secrets, decodeTools("Todos:on,Diary:on,AiDiary:off,Weather:off"))
    }

    @Test
    fun whatVersion030WroteStillReads() {
        // 0.3.0 listed only the groups that were on, and knew Todos, Diary and Weather.
        assertEquals(
            setOf(ToolGroup.Todos, ToolGroup.Weather, ToolGroup.AiDiary, ToolGroup.Secrets),
            decodeTools("Todos,Weather"),
        )
        // Everything switched off then stays off; the newer groups still start on.
        assertEquals(setOf(ToolGroup.AiDiary, ToolGroup.Secrets), decodeTools(""))
    }

    @Test
    fun unknownNamesAreSkipped() {
        assertEquals(AppSettings().tools - ToolGroup.Todos, decodeTools("Todos:off,Teleport:on"))
    }
}
