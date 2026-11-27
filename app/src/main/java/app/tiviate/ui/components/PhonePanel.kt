package app.tiviate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.tiviate.core.qr.QrCode
import app.tiviate.data.PhoneLink
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Easier with your phone": the address, a QR code that opens it with the code filled in, and the code. What the
 * phone sends goes to [onReceive] (on the main thread); the page is served only while this panel is shown.
 */
@Composable
fun PhonePanel(form: PhoneLink.Form, onReceive: suspend (Map<String, String>) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val receive by rememberUpdatedState(onReceive)
    var status by remember { mutableStateOf<String?>(null) }
    val link = remember(form) {
        PhoneLink(form) { fields ->
            scope.launch(Dispatchers.Main) {
                status = t("Received from your phone.")
                receive(fields)
            }
        }
    }
    var running by remember { mutableStateOf(false) }
    DisposableEffect(link) {
        running = link.start()
        onDispose { link.stop() }
    }
    val code by link.code.collectAsState()
    val address = remember(running) { if (running) link.address() else null }
    Column(
        modifier.background(Tokens.Surface, Tokens.SmallShape).padding(16.dp).testTag("phone_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t("Easier with your phone"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        if (address == null) {
            Text(t("Connect this TV to your Wi-Fi or network to send the details from a phone."), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            return@Column
        }
        Step(1, t("Connect your phone to the same Wi-Fi as this TV."))
        Step(2, t("Scan the code with the phone's camera, or open this address in its browser:"))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            QrImage("$address/?code=$code", 112.dp, Modifier.testTag("phone_qr"))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(address, modifier = Modifier.testTag("phone_address"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                Text(t("Code"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                Text(code, modifier = Modifier.testTag("phone_code"), style = TextStyle(color = Tokens.Cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp))
            }
        }
        Step(3, t("Enter the code shown here (scanning fills it in)."))
        Step(4, if (form == PhoneLink.Form.PLAYLIST) t("Send your login, a link or an M3U file. The playlist then loads here.") else t("Paste your keys and send them. They're saved and checked here."))
        status?.let { Text(it, modifier = Modifier.testTag("phone_status"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold)) }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).background(Tokens.Selected, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Bold))
        }
        Text(text, modifier = Modifier.fillMaxWidth(), style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize))
    }
}

/** [text] as a QR code: dark modules on white, with the quiet zone phones need around it. */
@Composable
fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val grid = remember(text) { QrCode.encode(text) } ?: return
    Canvas(modifier.size(size).semantics { contentDescription = text }) {
        val quiet = 4
        val n = grid.size + 2 * quiet
        val cell = this.size.width / n
        drawRect(Color.White)
        for (y in grid.indices) for (x in grid.indices) {
            if (grid[y][x]) drawRect(Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
