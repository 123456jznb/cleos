package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.TodoDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LocationTest {
    /** What BigDataCloud answered for a spot in Hangzhou, cut down to what is read. */
    private val hangzhou = """{
        "latitude": 30.27412, "longitude": 120.15513, "countryName": "中华人民共和国", "countryCode": "CN",
        "principalSubdivision": "浙江省", "city": "杭州市", "locality": "拱墅区",
        "localityInfo": {"administrative": [
            {"name": "中华人民共和国", "order": 2, "adminLevel": 2},
            {"name": "杭州市", "order": 6, "adminLevel": 5},
            {"name": "浙江省", "order": 5, "adminLevel": 4},
            {"name": "拱墅区", "order": 7, "adminLevel": 6}
        ], "informative": [{"name": "亚洲", "order": 1}]}
    }"""

    @Test
    fun aPlaceInChinaIsNamedTheChineseWay() {
        assertEquals("浙江省杭州市拱墅区" to "杭州市拱墅区", Locations.fromBigDataCloud(hangzhou))
        // Without the levels, the three names it always gives.
        val bare = """{"countryCode":"CN","principalSubdivision":"上海市","city":"上海市","locality":"徐汇区"}"""
        assertEquals("上海市徐汇区" to "上海市徐汇区", Locations.fromBigDataCloud(bare))
        val abroad = """{"countryCode":"JP","principalSubdivision":"东京都","city":"新宿区","locality":"新宿"}"""
        assertEquals("东京都, 新宿区, 新宿" to "新宿区, 新宿", Locations.fromBigDataCloud(abroad))
        assertEquals(null to null, Locations.fromBigDataCloud("""{"countryCode":"CN"}"""))
        assertEquals(null to null, Locations.fromBigDataCloud("<html>busy</html>"))
    }

    @Test
    fun theModelReadsWhereAndHowSure() {
        val fresh = Place(30.274123, 120.155131, 35.4f, ageMs = 5_000, address = "浙江省杭州市拱墅区", area = "杭州市拱墅区")
        assertEquals("对方现在在：浙江省杭州市拱墅区\n坐标（WGS-84）：30.27412, 120.15513，误差约 35 米", Locations.describe(fresh))
        val old = fresh.copy(accuracy = null, ageMs = 12 * 60_000, address = null)
        assertEquals("查到了坐标，但没查到地名。\n坐标（WGS-84）：30.27412, 120.15513\n这是 12 分钟前的位置，这次没拿到更新的。", Locations.describe(old))
    }

    @Test
    fun theToolSaysWhereAndTheChatSaysItLooked() = runBlocking {
        val place = Place(30.27412, 120.15513, 20f, 1_000, "浙江省杭州市拱墅区", "杭州市拱墅区")
        val on = AppSettings().let { it.copy(tools = it.tools + ToolGroup.Location) }
        val call = ToolCall("c1", ToolSpecs.getLocation.name, "{}")
        val found = box(object : LocationSource {
            override suspend fun here() = place
        }).run(call, on)
        assertTrue(found.result.startsWith("对方现在在：浙江省杭州市拱墅区"))
        assertEquals("查了你的位置：杭州市拱墅区", found.note)
        // Off unless switched on; and a phone that can't say where it is says why.
        assertFalse(ToolGroup.Location in AppSettings().tools)
        assertEquals("查位置没成：设置里关着", box(null).run(call, AppSettings()).note)
        val refused = box(object : LocationSource {
            override suspend fun here(): Place = throw ToolFailure("对方没给 App 定位权限，现在查不了。", "没有定位权限")
        }).run(call, on)
        assertEquals("查位置没成：没有定位权限", refused.note)
        assertNull(ToolSpecs.offered(AppSettings().tools).firstOrNull { it.name == ToolSpecs.getLocation.name })
        assertTrue(ToolSpecs.offered(on.tools).any { it.name == ToolSpecs.getLocation.name })
    }

    private fun box(location: LocationSource?) = ToolBox(unused(), unused(), unused(), location = location)

    /** A DAO or source the location tool never touches. */
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("not used") } as T
}
