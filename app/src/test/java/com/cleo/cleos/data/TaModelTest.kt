package com.cleo.cleos.data

import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.TaModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which model a TA's words come from: the chat one, or the one for words that are heard. */
class TaModelTest {
    private val ta = CompanionEntity(apiBaseUrl = "https://api.deepseek.com", apiModel = "deepseek-v4-flash", createdAt = 0)
    private val chat = TaModel("https://api.deepseek.com", "deepseek-v4-flash", forHeard = false)
    private val withHeard = ta.copy(spokenModelOn = true, spokenApiBaseUrl = " https://relay.example/v1 ", spokenApiModel = " claude-test ")

    @Test
    fun typedWordsAlwaysComeFromTheChatModel() {
        assertEquals(chat, withHeard.modelFor(heard = false))
    }

    @Test
    fun heardWordsComeFromTheOtherOneWhenItIsOn() {
        assertEquals(TaModel("https://relay.example/v1", "claude-test", forHeard = true), withHeard.modelFor(heard = true))
    }

    @Test
    fun offTheChatModelSpeaksToo() {
        assertEquals(chat, withHeard.copy(spokenModelOn = false).modelFor(heard = true))
    }

    @Test
    fun halfFilledInCountsAsOff() {
        assertEquals(chat, withHeard.copy(spokenApiModel = "  ").modelFor(heard = true))
        assertEquals(chat, withHeard.copy(spokenApiBaseUrl = "").modelFor(heard = true))
    }
}
