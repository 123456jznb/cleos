package com.cleo.cleos.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Where the phone is: the fix, and what the place is called. */
data class Place(
    val lat: Double,
    val lon: Double,
    /** How far off the fix may be, in metres; null when the phone didn't say. */
    val accuracy: Float?,
    /** How old the fix was when it was read, in milliseconds. */
    val ageMs: Long,
    /** The place, as closely as it could be named (浙江省杭州市拱墅区…); null when nothing named it. */
    val address: String?,
    /** City and district, for the line in the chat: 杭州市拱墅区. */
    val area: String?,
)

/** The phone's location, for get_location. Throws [ToolFailure] when there is none to give. */
interface LocationSource {
    suspend fun here(): Place
}

/** The parts of finding out where the phone is that don't need the phone. */
object Locations {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the model reads. */
    fun describe(p: Place): String = buildString {
        append(p.address?.let { "对方现在在：$it" } ?: "查到了坐标，但没查到地名。")
        append("\n坐标（WGS-84）：").append(String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon))
        p.accuracy?.let { append("，误差约 ").append(it.roundToInt()).append(" 米") }
        val minutes = p.ageMs / 60_000
        if (minutes >= 2) append("\n这是 $minutes 分钟前的位置，这次没拿到更新的。")
    }

    /**
     * BigDataCloud's answer as an address and the area for the chat line: the administrative
     * levels below the country, in order. Chinese names run together (浙江省杭州市拱墅区).
     */
    fun fromBigDataCloud(body: String): Pair<String?, String?> {
        val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null to null
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
        val china = o.str("countryCode") == "CN"
        val levels = ((o["localityInfo"] as? JsonObject)?.get("administrative") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.int("adminLevel") > 2 }
            .sortedBy { it.int("order") }
            .mapNotNull { it.str("name") }
        val names = levels.ifEmpty { listOfNotNull(o.str("principalSubdivision"), o.str("city"), o.str("locality")) }.distinct()
        if (names.isEmpty()) return null to null
        val area = listOfNotNull(o.str("city"), o.str("locality")).distinct().ifEmpty { listOf(names.last()) }
        return names.joinToString(if (china) "" else ", ") to area.joinToString(if (china) "" else ", ")
    }

    /** The phone's own geocoder's answer, the same way. */
    fun fromAddress(a: Address): Pair<String?, String?> {
        val parts = listOfNotNull(a.adminArea, a.locality, a.subLocality, a.thoroughfare, a.featureName).distinct()
        val line = a.getAddressLine(0)?.trim()?.ifEmpty { null } ?: parts.joinToString("").ifEmpty { null }
        val area = listOfNotNull(a.locality, a.subLocality).distinct().joinToString("").ifEmpty { a.adminArea }
        return line to area
    }
}

/**
 * The phone's location. There are no Google services on the phone, so the system's own
 * providers are asked: a fix from the last couple of minutes is used as it is; otherwise one
 * is asked for (fused where the phone has it, else the network one, else GPS); and if none
 * comes, the last one known, saying how old it is. Only while a reply asks: nothing runs in
 * the background.
 *
 * Named by the phone's own geocoder when it has one, else by BigDataCloud's free lookup for
 * apps (it answers from China, where OpenStreetMap's didn't), else not named at all.
 */
class PhoneLocation(private val context: Context, http: OkHttpClient) : LocationSource {
    private val http = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    override suspend fun here(): Place {
        if (!allowed(context)) throw ToolFailure("对方没给 App 定位权限，现在查不了。", "没有定位权限")
        val lm = context.getSystemService(LocationManager::class.java) ?: throw ToolFailure("这台手机查不了位置。", "手机不支持")
        if (!lm.isLocationEnabled) throw ToolFailure("对方手机的定位服务关着，查不了；可以请对方打开再试。", "手机的定位没开")
        val fix = recent(lm) ?: current(lm) ?: last(lm)
            ?: throw ToolFailure("这会儿没拿到位置（可能在室内、信号不好），过一会儿再试。", "没拿到位置")
        val (address, area) = geocoder(fix) ?: lookup(fix) ?: (null to null)
        return Place(fix.latitude, fix.longitude, fix.takeIf { it.hasAccuracy() }?.accuracy, age(fix), address, area)
    }

    private fun age(fix: Location) = ((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)

    @SuppressLint("MissingPermission")
    private fun known(lm: LocationManager): List<Location> =
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }

    /** A fix from the last two minutes, good to a couple of hundred metres: no need to wait for one. */
    private fun recent(lm: LocationManager) =
        known(lm).filter { age(it) < 2 * 60_000 && (!it.hasAccuracy() || it.accuracy <= 200f) }.minByOrNull { age(it) }

    /** The freshest fix known from the last six hours. */
    private fun last(lm: LocationManager) = known(lm).filter { age(it) < 6 * 3_600_000L }.minByOrNull { age(it) }

    @SuppressLint("MissingPermission")
    private suspend fun current(lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val enabled = lm.getProviders(true)
        // "fused" is the system's own (LocationManager.FUSED_PROVIDER, API 31), not Google's.
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    private suspend fun geocoder(fix: Location): Pair<String?, String?>? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { cont ->
                    g.getFromLocation(
                        fix.latitude,
                        fix.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())

                            override fun onError(errorMessage: String?) = cont.resume(null)
                        },
                    )
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
        if (address == null) Log.i(TAG, "the phone's geocoder named nothing")
        return address?.let(Locations::fromAddress)?.takeIf { it.first != null }
    }

    private suspend fun lookup(fix: Location): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=${fix.latitude}&longitude=${fix.longitude}&localityLanguage=zh-Hans"
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) Locations.fromBigDataCloud(r.body.string()) else null.also { Log.w(TAG, "lookup: HTTP ${r.code}") }
            }
        }.onFailure { Log.w(TAG, "lookup: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()?.takeIf { it.first != null }
    }

    companion object {
        private const val TAG = "PhoneLocation"

        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** Either will do: approximate is enough to name the district. */
        fun allowed(context: Context) =
            PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
