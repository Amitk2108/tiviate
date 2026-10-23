package app.tiviate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.t

/**
 * Remote-friendly text input using the TV "click to edit" pattern: the field is an ordinary focusable
 * row (so the D-pad moves freely between fields), OK starts editing and opens the keyboard, and the
 * keyboard's Next/Done key returns to D-pad navigation on the following field.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Uri,
    password: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val finish = {
        editing = false
        focusManager.moveFocus(FocusDirection.Down)
        Unit
    }
    LaunchedEffect(editing) { if (editing) runCatching { input.requestFocus() } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
        FocusSurface(
            onClick = { editing = true },
            modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
            shape = Tokens.SmallShape,
            scale = 1f,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize),
                cursorBrush = SolidColor(Tokens.Cyan),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onNext = { finish() }, onDone = { finish() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(input)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false }
                    .then(if (tag != null) Modifier.testTag("${tag}_input") else Modifier)
                    // While editing without the keyboard shown, Up/Down still leave the field.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { editing = false; focusManager.moveFocus(FocusDirection.Down) }
                            Key.DirectionUp -> { editing = false; focusManager.moveFocus(FocusDirection.Up) }
                            else -> false
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = TextStyle(color = Tokens.TextSecondary.copy(alpha = 0.6f), fontSize = Tokens.BodySize))
                    inner()
                },
            )
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    tag: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
        container = if (primary) Tokens.Blue else Tokens.Raised,
        focusedContainer = if (primary) Tokens.Cyan else Tokens.Outline,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            style = TextStyle(color = if (primary) Tokens.Background else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    FocusSurface(onClick = onToggle, modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
            Text(
                if (checked) t("On") else t("Off"),
                modifier = Modifier.width(48.dp),
                style = TextStyle(color = if (checked) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
