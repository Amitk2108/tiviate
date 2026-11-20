package app.tiviate.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.Reminder
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay

/** A programme with a reminder is about to start: watch it now, or not. Goes away by itself after a minute. */
@Composable
fun ReminderPrompt(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val watch = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(reminder) { focusSoon(watch) }
    LaunchedEffect(reminder) { delay(60_000); onDismiss() }
    Box(
        Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("reminder_prompt")
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).background(Tokens.Surface, Tokens.Shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(t("Reminder"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
            Text(reminder.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Text(tf("Starts at %1\$s on %2\$s", formatTime(reminder.startMs), reminder.channelName), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(t("Watch"), onWatch, Modifier.focusRequester(watch), primary = true, tag = "reminder_watch")
                ActionButton(t("Dismiss"), onDismiss, tag = "reminder_dismiss")
            }
        }
    }
}
