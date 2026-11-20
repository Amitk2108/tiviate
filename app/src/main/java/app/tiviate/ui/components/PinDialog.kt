package app.tiviate.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.Parental
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens

/**
 * Asks for the 4-digit parental PIN: the remote's number keys, the on-screen pad (D-pad or touch) or a
 * keyboard. [onPin] says whether the PIN is right; a wrong one clears the dots to try again.
 */
@Composable
fun PinDialog(title: String, onPin: (String) -> Boolean, onCancel: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { focusSoon(first) }
    fun add(d: Char) {
        if (digits.length >= Parental.PIN_LENGTH) return
        digits += d
        wrong = false
        if (digits.length == Parental.PIN_LENGTH && !onPin(digits)) {
            wrong = true
            digits = ""
        }
    }

    val sheet = usesBottomSheets()
    val pad = @Composable { PinPad(title, digits.length, wrong, first, onCancel, onDelete = { digits = digits.dropLast(1) }, onDigit = ::add) }
    if (sheet) {
        BottomSheet(onCancel, "pin_dialog") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { pad() } }
        return
    }
    Box(
        Modifier.fillMaxSize().background(Tokens.Scrim).testTag("pin_dialog")
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val n = e.nativeKeyEvent.keyCode
                when {
                    n in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { add('0' + (n - android.view.KeyEvent.KEYCODE_0)); true }
                    n in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> { add('0' + (n - android.view.KeyEvent.KEYCODE_NUMPAD_0)); true }
                    e.key == Key.Backspace || e.key == Key.Delete -> { digits = digits.dropLast(1); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.background(Tokens.Surface, Tokens.Shape).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { pad() }
    }
}

/** Title, the dots, and the 3 × 4 keypad. */
@Composable
private fun PinPad(title: String, entered: Int, wrong: Boolean, first: FocusRequester, onCancel: () -> Unit, onDelete: () -> Unit, onDigit: (Char) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("pin_dots")) {
            repeat(Parental.PIN_LENGTH) { i ->
                Box(Modifier.size(16.dp).background(if (i < entered) Tokens.Cyan else Tokens.Outline, CircleShape))
            }
        }
        Text(if (wrong) t("Wrong PIN") else " ", style = TextStyle(color = Tokens.Danger, fontSize = Tokens.CaptionSize))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "cancel", "0", "delete")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val label = when (k) { "cancel" -> t("Cancel"); "delete" -> "⌫"; else -> k }
                    FocusSurface(
                        onClick = {
                            when (k) {
                                "cancel" -> onCancel()
                                "delete" -> onDelete()
                                else -> onDigit(k[0])
                            }
                        },
                        modifier = (if (k == "1") Modifier.focusRequester(first) else Modifier).size(width = 84.dp, height = 52.dp).testTag("pin_$k"),
                        shape = Tokens.SmallShape,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(label, style = TextStyle(color = Tokens.Text, fontSize = if (k.length == 1) Tokens.HeadingSize else Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}
