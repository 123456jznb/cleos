package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.TodoDao
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ToolBoxTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis = ZonedDateTime.of(2026, 9, 23, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 9, 23)
    private val todos = FakeTodos()
    private val diary = FakeDiary()
    private val weatherCalls = mutableListOf<String>()
    private val weather = object : WeatherSource {
        override suspend fun report(city: String, days: Int): WeatherReport {
            weatherCalls += city
            return WeatherReport(city, "地点：$city\n现在：晴")
        }
    }
    private val box = ToolBox(todos, diary, weather, clock = { nowMillis }, zone = { zone })
    private val all = AppSettings(tools = ToolGroup.entries.toSet())

    private fun run(name: String, args: String, settings: AppSettings = all) =
        runBlocking { box.run(ToolCall("c", name, args), settings) }

    @Test
    fun addTodoStoresItAndSaysWhere() {
        val out = run("add_todo", """{"title":" 交报告 ","due":"2026-9-24","note":"发到邮箱"}""")
        val t = todos.rows.single()
        assertEquals("交报告", t.title)
        assertEquals(LocalDate.of(2026, 9, 24).toEpochDay(), t.dueDay)
        assertEquals("发到邮箱", t.note)
        assertTrue(out.result, out.result.contains("#${t.id} 交报告"))
        assertTrue("the model can check its date arithmetic", out.result.contains("2026-09-24（周四，明天）"))
        assertEquals("记下了待办「交报告」 · 9月24日", out.note)
    }

    @Test
    fun aDateItCannotReadIsSentBackNotGuessed() {
        val out = run("add_todo", """{"title":"交报告","due":"明天"}""")
        assertTrue(todos.rows.isEmpty())
        assertEquals("记待办没成：日期没写对", out.note)
        assertTrue(out.result.contains("2026-09-24"))
    }

    @Test
    fun listShowsDatedFirstAndFlagsOverdue() {
        todos.rows += TodoEntity(id = 1, title = "买牛奶", createdAt = 1)
        todos.rows += TodoEntity(id = 2, title = "交报告", dueDay = today.minusDays(3).toEpochDay(), createdAt = 2)
        todos.rows += TodoEntity(id = 3, title = "洗衣服", done = true, doneAt = nowMillis, createdAt = 3)
        val out = run("list_todos", "")
        val lines = out.result.lines()
        assertEquals("没做完的 2 条：", lines[0])
        assertTrue(lines[1], lines[1].startsWith("#2 交报告") && lines[1].endsWith("已过期"))
        assertTrue(lines[2].startsWith("#1 买牛奶"))
        assertFalse(out.result.contains("洗衣服"))
        assertEquals("看了一眼待办（2 条没做完）", out.note)
        assertTrue(run("list_todos", """{"include_done":true}""").result.contains("#3 洗衣服"))
    }

    @Test
    fun tickingATodoAndEmptyFieldsTouchNothingElse() {
        todos.rows += TodoEntity(id = 7, title = "交报告", note = "发到邮箱", dueDay = today.toEpochDay(), createdAt = 1)
        // Weaker models fill every optional field with "".
        val out = run("update_todo", """{"id":"#7","done":true,"title":"","note":"","due":""}""")
        val t = todos.rows.single()
        assertTrue(t.done)
        assertEquals(nowMillis, t.doneAt)
        assertEquals("发到邮箱", t.note)
        assertEquals(today.toEpochDay(), t.dueDay)
        assertEquals("把「交报告」打了勾", out.note)
    }

    @Test
    fun noneClearsADate() {
        todos.rows += TodoEntity(id = 7, title = "交报告", dueDay = today.toEpochDay(), createdAt = 1)
        val out = run("update_todo", """{"id":7,"due":"none"}""")
        assertNull(todos.rows.single().dueDay)
        assertEquals("改了待办「交报告」", out.note)
    }

    @Test
    fun anUnknownIdSaysSo() {
        val out = run("update_todo", """{"id":99,"done":true}""")
        assertEquals("改待办没成：没找到这一条", out.note)
        assertTrue(out.result.contains("list_todos"))
    }

    @Test
    fun theDiaryStaysClosedUnlessAllowed() {
        diary.rows += entry(1, today, "今天", "去了河边")
        val out = run("read_diary", "{}", AppSettings())
        assertEquals("翻日记没成：设置里关着", out.note)
        assertFalse(out.result.contains("河边"))
    }

    @Test
    fun searchingTheDiaryMatchesWhatWasWrittenNotTheJson() {
        diary.rows += entry(1, today.minusDays(2), "周一", "猫睡在窗台上")
        diary.rows += entry(2, today, "今天", "什么都没发生")
        val cat = run("read_diary", """{"query":"猫"}""")
        assertTrue(cat.result.contains("猫睡在窗台上"))
        assertEquals("在日记里找了「猫」（1 篇）", cat.note)
        // "text" is in every entry's JSON ({"type":"text",...}) but in no one's words.
        assertEquals("在日记里找了「text」：没找到", run("read_diary", """{"query":"text"}""").note)
    }

    @Test
    fun aLongEntryIsCutWithTheRestCounted() {
        diary.rows += entry(1, today, "长", "字".repeat(2000))
        val out = run("read_diary", """{"date":"2026-09-23"}""")
        assertTrue(out.result.contains("……（后面还有 500 字）"))
        assertEquals("读了9月23日的日记", out.note)
    }

    @Test
    fun weatherFallsBackToTheCityInSettingsAndAsksWhenThereIsNone() {
        assertEquals("查了杭州的天气", run("get_weather", "{}", all.copy(weatherCity = "杭州")).note)
        assertEquals(listOf("杭州"), weatherCalls)
        val none = run("get_weather", "{}")
        assertEquals("查天气没成：不知道在哪个城市", none.note)
        assertEquals(1, weatherCalls.size)
    }

    @Test
    fun brokenArgumentsAndUnknownToolsAreReportedNotThrown() {
        assertEquals("记待办没成：参数写错了", run("add_todo", """{"title":"交""").note)
        assertEquals("想用的工具不存在：delete_everything", run("delete_everything", "{}").note)
    }

    private fun entry(id: Long, day: LocalDate, title: String, text: String) = DiaryEntryEntity(
        id = id,
        day = day.toEpochDay(),
        title = title,
        blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(text))),
        createdAt = id,
        updatedAt = id,
    )
}

private class FakeTodos : TodoDao {
    val rows = mutableListOf<TodoEntity>()
    override fun observeAll(): Flow<List<TodoEntity>> = flowOf(rows.toList())
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun insert(todo: TodoEntity): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0) + 1
        rows += todo.copy(id = id)
        return id
    }
    override suspend fun upsert(todo: TodoEntity) {
        rows.removeAll { it.id == todo.id }
        rows += todo
    }
    override suspend fun delete(todo: TodoEntity) {
        rows.removeAll { it.id == todo.id }
    }
    override suspend fun all() = rows.toList()
    override suspend fun insertAll(items: List<TodoEntity>) {
        rows += items
    }
    override suspend fun clear() = rows.clear()
}

private class FakeDiary : DiaryDao {
    val rows = mutableListOf<DiaryEntryEntity>()
    private val newest get() = rows.sortedWith(compareByDescending<DiaryEntryEntity> { it.day }.thenByDescending { it.createdAt })
    override fun observeAll(): Flow<List<DiaryEntryEntity>> = flowOf(newest)
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun onDay(day: Long) = rows.filter { it.day == day }
    override suspend fun recent(limit: Int) = newest.take(limit)

    /** LIKE '%q%' with ! as the escape character, as the real query. */
    override suspend fun search(pattern: String, limit: Int): List<DiaryEntryEntity> {
        val q = pattern.removePrefix("%").removeSuffix("%").replace("!%", "%").replace("!_", "_").replace("!!", "!")
        return newest.filter { it.title.contains(q, ignoreCase = true) || it.blocks.contains(q, ignoreCase = true) }.take(limit)
    }
    override suspend fun insert(entry: DiaryEntryEntity): Long {
        rows += entry
        return entry.id
    }
    override suspend fun update(entry: DiaryEntryEntity) {
        rows.replaceAll { if (it.id == entry.id) entry else it }
    }
    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }
    override suspend fun all() = rows.toList()
    override suspend fun insertAll(items: List<DiaryEntryEntity>) {
        rows += items
    }
    override suspend fun clear() = rows.clear()
}
