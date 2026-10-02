package com.cleo.cleos.ui.todo

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodoState(val pending: List<TodoEntity> = emptyList(), val done: List<TodoEntity> = emptyList(), val loaded: Boolean = false)

class TodoViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<TodoState> = c.db.todos().observeAll().map { all ->
        TodoState(
            // Things with a date first, soonest first; the rest in the order they were added.
            pending = all.filter { !it.done }.sortedWith(compareBy<TodoEntity> { it.dueDay ?: Long.MAX_VALUE }.thenBy { it.createdAt }),
            done = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0L },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoState())

    fun add(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        c.appScope.launch { c.db.todos().insert(TodoEntity(title = t, createdAt = System.currentTimeMillis())) }
    }

    fun toggle(todo: TodoEntity) {
        c.appScope.launch {
            c.db.todos().upsert(todo.copy(done = !todo.done, doneAt = if (todo.done) null else System.currentTimeMillis()))
        }
    }

    fun save(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().upsert(todo) }
    }

    fun delete(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().delete(todo) }
    }

    /** Puts a deleted item back exactly as it was, id included. */
    fun restore(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().upsert(todo) }
    }
}

@Composable
fun TodoTab(bottomInset: Dp) {
    val vm = appViewModel { TodoViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var barHeight by remember { mutableIntStateOf(0) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val barBottom = if (imeBottom > bottomInset) imeBottom + 8.dp else bottomInset

    // After adding, bring the new item (the last undated one) into view.
    var lastCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.pending.size) {
        if (lastCount >= 0 && state.pending.size > lastCount) listState.animateScrollToItem(state.pending.lastIndex)
        lastCount = state.pending.size
    }

    fun deleteWithUndo(todo: TodoEntity) {
        vm.delete(todo)
        scope.launch {
            // An explicit duration: with an action button the default is "until dismissed".
            val result = snackbar.showSnackbar(
                "删掉了「${todo.title}」",
                actionLabel = "撤销",
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(todo)
        }
    }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "待办",
                subtitle = when {
                    !state.loaded || (state.pending.isEmpty() && state.done.isEmpty()) -> null
                    state.pending.isEmpty() -> "都做完了"
                    else -> "还有 ${state.pending.size} 件"
                },
                backdrop = page,
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = barBottom)
                    .onSizeChanged { barHeight = it.height },
            ) {
                SnackbarHost(snackbar, Modifier.padding(horizontal = 8.dp))
                AddBar(
                    backdrop = page,
                    text = input,
                    onTextChange = { input = it },
                    onAdd = {
                        vm.add(input)
                        input = ""
                    },
                )
            }
        },
    ) {
        if (state.loaded && state.pending.isEmpty() && state.done.isEmpty()) {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center),
                style = palette.notice,
                shape = GlassShape.Rounded(22.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有待办", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(6.dp))
                    Text("在下面写一件要做的事", color = palette.contentSecondary, fontSize = 14.sp)
                }
            }
        }
        val barTop = barBottom + with(density) { barHeight.toDp() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight, bottom = barTop),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = statusTop + TopBarHeight + 8.dp,
                bottom = barTop + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.pending, key = { it.id }) { todo ->
                TodoRow(
                    todo = todo,
                    onToggle = { vm.toggle(todo) },
                    onClick = { editing = todo },
                    onLongClick = { deleteWithUndo(todo) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.done.isNotEmpty()) {
                item(key = "done-header") {
                    DoneHeader(state.done.size, showDone, { showDone = !showDone }, Modifier.animateItem())
                }
                if (showDone) {
                    items(state.done, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            onToggle = { vm.toggle(todo) },
                            onClick = { editing = todo },
                            onLongClick = { deleteWithUndo(todo) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    editing?.let { todo ->
        TodoEditDialog(
            todo = todo,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onDelete = {
                editing = null
                deleteWithUndo(todo)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    todo: TodoEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick),
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckCircle(todo.done, onToggle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    todo.title,
                    color = if (todo.done) palette.contentSecondary else palette.content,
                    fontSize = 16.sp,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (todo.note.isNotBlank()) {
                    Text(todo.note, color = palette.contentSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                todo.dueDay?.let { day ->
                    val date = LocalDate.ofEpochDay(day)
                    val overdue = !todo.done && date.isBefore(Dates.today())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        val color = if (overdue) palette.error else palette.accentContent
                        Icon(Icons.Rounded.Event, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(Dates.due(date), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckCircle(done: Boolean, onToggle: () -> Unit) {
    val palette = LocalGlassPalette.current
    val fill by animateColorAsState(if (done) palette.accent else Color.Transparent, label = "check")
    Box(
        Modifier
            .size(26.dp)
            .background(fill, CircleShape)
            .border(1.8.dp, if (done) palette.accent else palette.content.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Rounded.Check, contentDescription = "已完成", tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    Box(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
        GlassSurface(
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onToggle),
            style = palette.bar,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("做完的 $count 件", color = palette.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = palette.contentSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddBar(backdrop: Backdrop, text: String, onTextChange: (String) -> Unit, onAdd: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 50.dp)
                .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("添加一件事…", color = palette.contentSecondary, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.content, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accentContent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "添加待办" },
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            Icons.Rounded.Add,
            "添加",
            onAdd,
            backdrop,
            style = palette.accentSurface,
            tint = Color.White,
            enabled = text.isNotBlank(),
            size = 50.dp,
        )
    }
}
