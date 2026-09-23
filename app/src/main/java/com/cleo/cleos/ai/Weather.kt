package com.cleo.cleos.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * open-meteo: free, no key, and reachable from mainland China without a proxy
 * (measured 1–2 s for each of the two requests).
 */
class OpenMeteo(http: OkHttpClient) : WeatherSource {
    // The chat client waits minutes for slow models; a weather lookup should give up sooner.
    private val http = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun report(city: String, days: Int): WeatherReport {
        try {
            var place: Weather.Place? = null
            for (name in Weather.names(city)) {
                place = Weather.pickPlace(get(geocodeUrl(name)))
                if (place != null) break
            }
            if (place == null) {
                throw ToolFailure(
                    "没找到叫「$city」的地方。换个写法再查：中国的城市用中文名、不带“市”字，国外的用英文名。",
                    "没找到「$city」这个地方",
                )
            }
            return WeatherReport(place.name, Weather.describe(place, get(forecastUrl(place, days))))
        } catch (e: IOException) {
            throw ToolFailure("天气服务连不上（${e.message ?: e.javaClass.simpleName}），告诉对方现在查不到。", "网络出错")
        } catch (e: IllegalArgumentException) {
            // SerializationException is one of these, and so is .jsonObject on a non-object.
            throw ToolFailure("天气服务返回的内容读不懂，告诉对方现在查不到。", "天气服务出错")
        }
    }

    private fun geocodeUrl(name: String): HttpUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
        .addQueryParameter("name", name)
        .addQueryParameter("count", "10")
        .addQueryParameter("language", "zh")
        .build()

    private fun forecastUrl(place: Weather.Place, days: Int): HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", place.latitude.toString())
        .addQueryParameter("longitude", place.longitude.toString())
        .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
        .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
        .addQueryParameter("timezone", "auto")
        .addQueryParameter("forecast_days", days.toString())
        .build()

    private suspend fun get(url: HttpUrl): JsonObject {
        http.newCall(Request.Builder().url(url).get().build()).await().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return json.parseToJsonElement(body).jsonObject
        }
    }

    /** Enqueued rather than executed, so pressing stop cancels the request instead of waiting it out. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, r, _ -> r.close() }
            }
        })
    }
}

/** The parts of the weather lookup that are plain data, kept apart for testing. */
internal object Weather {
    data class Place(
        val name: String,
        val region: String?,
        val country: String?,
        val latitude: Double,
        val longitude: Double,
        val population: Long,
    ) {
        /** 杭州（浙江，中国）: enough for the model to notice it got a different 朝阳 than meant. */
        val label: String
            get() = name + listOfNotNull(region, country).distinct().takeIf { it.isNotEmpty() }
                ?.joinToString("，", "（", "）").orEmpty()
    }

    /** The geocoder knows 杭州 but not 杭州市 or 朝阳区, so a second try drops the suffix. */
    fun names(city: String): List<String> {
        val c = city.trim()
        val bare = c.removeSuffix("市").removeSuffix("区").removeSuffix("县").removeSuffix("省")
        return listOf(c, bare).filter { it.length >= 2 }.distinct().ifEmpty { listOf(c) }
    }

    /**
     * The most populous match. The first one is often a village: 东京 finds two hamlets
     * in 江苏 and 浙江 before anything else, and 北京 also names a spot in 四川.
     */
    fun pickPlace(root: JsonObject): Place? {
        val results = root["results"] as? JsonArray ?: return null
        return results.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            Place(
                name = o.string("name") ?: return@mapNotNull null,
                region = o.string("admin1"),
                country = o.string("country"),
                latitude = o.double("latitude") ?: return@mapNotNull null,
                longitude = o.double("longitude") ?: return@mapNotNull null,
                population = o.double("population")?.toLong() ?: 0L,
            )
        }.maxByOrNull { it.population }
    }

    fun describe(place: Place, forecast: JsonObject): String = buildString {
        append("地点：").append(place.label)
        (forecast["current"] as? JsonObject)?.let { c ->
            append("\n现在：").append(codeText(c.int("weather_code")))
            c.double("temperature_2m")?.let { append("，").append(it.roundToInt()).append("°C") }
            c.double("apparent_temperature")?.let { append("（体感 ").append(it.roundToInt()).append("°C）") }
            c.int("relative_humidity_2m")?.let { append("，湿度 ").append(it).append('%') }
            c.double("wind_speed_10m")?.let { append("，风速 ").append(it.roundToInt()).append(" km/h") }
        }
        val daily = forecast["daily"] as? JsonObject ?: return@buildString
        val dates = (daily["time"] as? JsonArray).orEmpty()
        dates.forEachIndexed { i, t ->
            val date = (t as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@forEachIndexed
            // Relative to the place's own calendar (timezone=auto), not the phone's.
            val relative = when (i) {
                0 -> "（今天）"
                1 -> "（明天）"
                2 -> "（后天）"
                else -> ""
            }
            append('\n').append(Describe.monthDay(date)).append(' ').append(Describe.weekday(date)).append(relative)
            append("：").append(codeText(daily.intAt("weather_code", i)))
            val low = daily.doubleAt("temperature_2m_min", i)
            val high = daily.doubleAt("temperature_2m_max", i)
            if (low != null && high != null) append("，").append(low.roundToInt()).append("～").append(high.roundToInt()).append("°C")
            daily.intAt("precipitation_probability_max", i)?.let { append("，降水概率 ").append(it).append('%') }
        }
    }

    /** WMO weather interpretation codes, as open-meteo reports them. */
    fun codeText(code: Int?): String = when (code) {
        0 -> "晴"
        1 -> "大体晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85 -> "阵雪"
        86 -> "强阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨，有冰雹"
        null -> "天气不明"
        else -> "天气代码 $code"
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.doubleAt(key: String, i: Int) = ((this[key] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.intAt(key: String, i: Int) = ((this[key] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.intOrNull
}
