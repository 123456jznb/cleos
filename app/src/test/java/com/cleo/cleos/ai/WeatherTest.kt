package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun aSuffixedNameIsTriedBare() {
        assertEquals(listOf("杭州市", "杭州"), Weather.names("杭州市"))
        assertEquals(listOf("朝阳区", "朝阳"), Weather.names(" 朝阳区 "))
        assertEquals(listOf("Tokyo"), Weather.names("Tokyo"))
    }

    // Trimmed from real geocoder answers (count=10, language=zh).
    @Test
    fun theMostPopulousMatchWinsOverTheFirst() {
        val beijing = json(
            """{"results":[
              {"name":"北京","latitude":30.1,"longitude":107.9,"admin1":"重庆市","country":"中国"},
              {"name":"北京","latitude":39.9,"longitude":116.4,"admin1":"北京市","country":"中国","population":18960744}
            ]}""",
        )
        val p = Weather.pickPlace(beijing)!!
        assertEquals(39.9, p.latitude, 0.0)
        assertEquals("北京（北京市，中国）", p.label)
        assertNull(Weather.pickPlace(json("""{"generationtime_ms":0.14}""")))
    }

    @Test
    fun theReportNamesThePlaceAndLabelsTheDays() {
        val place = Weather.Place("杭州", "浙江", "中国", 30.29, 120.16, 9236032)
        // A real forecast response, trimmed.
        val forecast = json(
            """{"current":{"temperature_2m":31.7,"apparent_temperature":33.3,"relative_humidity_2m":45,"weather_code":3,"wind_speed_10m":9.4},
               "daily":{"time":["2026-09-23","2026-09-24","2026-09-25"],"weather_code":[3,3,51],
               "temperature_2m_max":[32.0,33.3,35.5],"temperature_2m_min":[23.5,23.8,24.0],
               "precipitation_probability_max":[10,6,47]}}""",
        )
        val lines = Weather.describe(place, forecast).lines()
        assertEquals("地点：杭州（浙江，中国）", lines[0])
        assertEquals("现在：阴，32°C（体感 33°C），湿度 45%，风速 9 km/h", lines[1])
        assertEquals("9月23日 周三（今天）：阴，24～32°C，降水概率 10%", lines[2])
        assertTrue(lines[4], lines[4].startsWith("9月25日 周五（后天）：毛毛雨"))
    }

    @Test
    fun weatherCodes() {
        assertEquals("晴", Weather.codeText(0))
        assertEquals("中雨", Weather.codeText(63))
        assertEquals("雷阵雨", Weather.codeText(95))
        assertEquals("天气不明", Weather.codeText(null))
    }
}
