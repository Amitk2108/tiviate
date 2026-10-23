package app.tiviate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.background

/** Remote-friendly text input: focus shows the cyan outline; OK opens the TV keyboard. */
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize),
            cursorBrush = SolidColor(Tokens.Cyan),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .background(if (focused) Tokens.Raised else Tokens.Surface, Tokens.SmallShape)
                .border(BorderStroke(if (focused) Tokens.FocusBorder else 1.dp, if (focused) Tokens.Cyan else Tokens.Outline), Tokens.SmallShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = TextStyle(color = Tokens.TextSecondary.copy(alpha = 0.6f), fontSize = Tokens.BodySize))
                inner()
            },
        )
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
                if (checked) "On" else "Off",
                modifier = Modifier.width(48.dp),
                style = TextStyle(color = if (checked) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
