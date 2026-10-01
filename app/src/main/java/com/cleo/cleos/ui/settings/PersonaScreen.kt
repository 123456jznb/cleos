package com.cleo.cleos.ui.settings

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One TA's persona as the person writes it, saved a moment after each change and once more on
 * leaving. The settings page only shows its start; this page is the one place it is written.
 */
class PersonaViewModel(private val c: AppContainer, private val companionId: Long) : ViewModel() {
    /** What the editor holds. It may run past [PERSONA_LIMIT] (it says so); only what fits is saved. */
    var text by mutableStateOf("")
    var name by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set

    /** The text as it was loaded or last saved, so leaving without a change writes nothing. */
    private var saved = ""

    init {
        viewModelScope.launch {
            val ta = c.db.companions().get(companionId)
            text = ta?.persona.orEmpty()
            saved = text
            name = ta?.name?.trim().orEmpty()
            loaded = true
            watch()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        snapshotFlow { text }.drop(1).debounce(500).collect { save(it) }
    }

    private suspend fun save(t: String) {
        if (t == saved) return
        saved = t
        c.companions.update(companionId) { it.copy(persona = t.take(PERSONA_LIMIT)) }
    }

    override fun onCleared() {
        if (!loaded) return
        val t = text
        c.appScope.launch { save(t) }
    }
}

/**
 * A page for the persona alone, since one can run to tens of thousands of characters
 * (Companions.PERSONA_LIMIT). The text is Android's own EditText, not a Compose field: with
 * 25,000 characters the Compose one took up to 0.6 s a key on the emulator, as it lays out the
 * whole text again and draws every line of it, on screen or not (traced: 46 ms building the
 * layout, 263 ms drawing). An EditText re-lays out the paragraph that changed and draws the
 * lines in sight.
 */
@Composable
fun PersonaScreen(companionId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "persona-$companionId") { PersonaViewModel(it, companionId) }
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottom = if (imeBottom > navBottom) imeBottom else navBottom
    val length = vm.text.length
    val over = length - PERSONA_LIMIT

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (vm.name.isEmpty()) "TA 的性格" else "${vm.name}的性格",
                subtitle = "$length / $PERSONA_LIMIT 字，自动保存",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            )
        },
    ) {
        if (!vm.loaded) return@GlassPage
        GlassSurface(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = bottom + 14.dp),
            shape = GlassShape.Rounded(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonaEditor(
                    initial = remember { vm.text },
                    onChange = { vm.text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                val note = when {
                    over > 0 -> "超了 $over 字，只会存前 $PERSONA_LIMIT 字。"
                    length > PERSONA_LONG -> "每条消息都会带着它：写得越长，每次花的 token 越多。"
                    else -> null
                }
                note?.let { Text(it, color = if (over > 0) palette.error else palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
            }
        }
    }
}

/** Past this a persona is long enough for its cost to be worth a word: it goes with every message. */
internal const val PERSONA_LONG = 10_000

/**
 * The EditText, in the glass palette's colours. [initial] is read once; after that the text is
 * the EditText's own, and [onChange] hears every change. With nothing written yet it takes the
 * keyboard straight away; a long one is left to be scrolled to the place to change.
 */
@Composable
private fun PersonaEditor(initial: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val changed by rememberUpdatedState(onChange)
    val text = palette.content.toArgb()
    val hint = palette.contentSecondary.toArgb()
    val accent = palette.accent.toArgb()
    AndroidView(
        factory = { context ->
            EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 25f, resources.displayMetrics).roundToInt()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                // Never the full-screen editing a keyboard can switch to (in landscape): it copies the whole text across.
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                isVerticalScrollBarEnabled = true
                // Room for a paste that is too long to show as too long; past this it is cut where it is pasted.
                filters = arrayOf(InputFilter.LengthFilter(PERSONA_LIMIT * 2))
                this.hint = "想让 TA 怎样说话、记得什么，都写在这里。可以不写。"
                setText(initial)
                doAfterTextChanged { changed(it?.toString().orEmpty()) }
                // Once it is on screen: before that it can't take focus.
                if (initial.isEmpty()) {
                    post {
                        requestFocus()
                        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
                    }
                }
            }
        },
        update = { edit ->
            // Only when the palette changes (the glass turned light or dark), not at every key.
            val colors = listOf(text, hint, accent)
            if (edit.tag != colors) {
                edit.tag = colors
                edit.setTextColor(text)
                edit.setHintTextColor(hint)
                edit.highlightColor = (accent and 0x00FFFFFF) or 0x4D000000
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, edit.resources.displayMetrics).roundToInt()
                edit.textCursorDrawable = GradientDrawable().apply {
                    setColor(accent)
                    setSize(width, 0)
                }
                val tint = ColorStateList.valueOf(accent)
                edit.textSelectHandle?.mutate()?.let { it.setTintList(tint); edit.setTextSelectHandle(it) }
                edit.textSelectHandleLeft?.mutate()?.let { it.setTintList(tint); edit.setTextSelectHandleLeft(it) }
                edit.textSelectHandleRight?.mutate()?.let { it.setTintList(tint); edit.setTextSelectHandleRight(it) }
            }
        },
        modifier = modifier,
    )
}
