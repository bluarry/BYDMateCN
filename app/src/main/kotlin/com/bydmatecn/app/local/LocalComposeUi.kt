package com.bydmatecn.app.local

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmatecn.app.data.local.LocalePreferences
import com.bydmatecn.app.ui.theme.BYDMateTheme
import com.bydmatecn.app.ui.theme.ScaledDialogContent

/** Observable UI models, not Android widgets. Operations remain owned by the activity. */
internal class LocalText(initial: String = "", private val changed: (String) -> Unit = {}) {
    private var value by mutableStateOf(initial)
    var text: String
        get() = value
        set(text) { value = text; changed(text) }
}
internal class LocalInput(val hint: String, initial: String = "", val limit: Int = Int.MAX_VALUE) {
    var text by mutableStateOf(initial)
}
internal class LocalSlider(initial: Int, val max: Int = 100) {
    var progress by mutableIntStateOf(initial)
}
class LocalUiState {
    val controls = mutableStateListOf<@Composable () -> Unit>()
    internal var dialog: LocalDialogBuilder? by mutableStateOf(null)
}
interface LocalComposeHost { val ui: LocalUiState }

internal fun ComponentActivity.installLocalContent(ui: LocalUiState, content: (@Composable () -> Unit)? = null) {
    val fontScale = LocalePreferences(this).getFontScale()
    setContent {
        BYDMateTheme(fontScale) {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (content == null) LocalPanel(ui, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) else content()
                LocalDialog(ui)
            }
        }
    }
}

@Composable
internal fun LocalPanel(ui: LocalUiState, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.controls.forEach { it() }
    }
}
internal fun LocalUiState.label(text: String, size: Float = 16f): LocalText {
    val value = LocalText(text)
    controls.add { Text(value.text, fontSize = size.sp, modifier = Modifier.fillMaxWidth()) }
    return value
}
internal fun LocalUiState.button(text: String, action: () -> Unit) {
    controls.add { Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) } }
}
internal fun LocalUiState.input(value: LocalInput) {
    controls.add { LocalInputField(value) }
}
@Composable
private fun LocalInputField(value: LocalInput) {
    OutlinedTextField(value = value.text, onValueChange = { value.text = it.take(value.limit) },
        label = { Text(value.hint) }, modifier = Modifier.fillMaxWidth())
}
internal fun LocalUiState.slider(value: LocalSlider, changed: (Int) -> Unit = {}, finished: () -> Unit = {}) {
    controls.add {
        Slider(value = value.progress.toFloat(), onValueChange = { value.progress = it.toInt(); changed(value.progress) },
            valueRange = 0f..value.max.toFloat(), onValueChangeFinished = finished, modifier = Modifier.fillMaxWidth())
    }
}
internal fun LocalUiState.toggle(text: String, initial: Boolean, changed: (Boolean) -> Unit) {
    controls.add {
        var checked by remember { mutableStateOf(initial) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = { checked = it; changed(it) })
        }
    }
}
internal fun LocalUiState.holdButton(text: String, pressed: () -> Unit, released: () -> Unit) {
    controls.add {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onPress = {
                    try { pressed(); tryAwaitRelease() } finally { released() }
                })
            }) { Text(text, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** Dialog descriptions are rendered as Material Compose dialogs, including directory and file lists. */
internal class LocalDialogBuilder(context: Context) {
    private val ui = (context as LocalComposeHost).ui
    var title = ""
    var message = ""
    var input: LocalInput? = null
    var items: List<String> = emptyList()
    var selected: ((Int) -> Unit)? = null
    var positive: Pair<String, () -> Unit>? = null
    var negative = "取消"
    var dismissed: () -> Unit = {}
    fun setTitle(value: String) = apply { title = value }
    fun setMessage(value: String) = apply { message = value }
    fun setView(value: LocalInput) = apply { input = value }
    fun setItems(values: Array<String>, action: (Any?, Int) -> Unit) = apply { items = values.toList(); selected = { action(null, it) } }
    fun setPositiveButton(text: String, action: (Any?, Int) -> Unit) = apply { positive = text to { action(null, 0) } }
    fun setNegativeButton(text: String, action: ((Any?, Int) -> Unit)?) = apply { negative = text; dismissed = { action?.invoke(null, 0) } }
    fun show() { ui.dialog = this }
}
@Composable
private fun LocalDialog(ui: LocalUiState) {
    val dialog = ui.dialog ?: return
    AlertDialog(onDismissRequest = { ui.dialog = null }, title = { Text(dialog.title) },
        text = { ScaledDialogContent {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dialog.message.isNotEmpty()) Text(dialog.message)
                dialog.input?.let { LocalInputField(it) }
                dialog.items.forEachIndexed { index, text ->
                    TextButton(onClick = { ui.dialog = null; dialog.selected?.invoke(index) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                }
            }
        } }, confirmButton = {
            dialog.positive?.let { (text, action) -> TextButton(onClick = { ui.dialog = null; action() }) { Text(text) } }
        }, dismissButton = { TextButton(onClick = { ui.dialog = null; dialog.dismissed() }) { Text(dialog.negative) } })
}
