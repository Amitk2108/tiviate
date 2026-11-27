package app.tiviate.data

import app.tiviate.ui.I18n
import app.tiviate.ui.t
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * "Easier with your phone": while a screen shows it, the TV serves a small page on the home network. The phone
 * opens it (QR code or address), and with the 4-digit code shown on the TV sends a playlist (login, link or M3U
 * file) or API keys. Only the right code is accepted; after [MAX_WRONG] wrong ones the TV shows a new code.
 */
class PhoneLink(private val form: Form, private val onReceive: (Map<String, String>) -> Unit) {
    enum class Form { PLAYLIST, KEYS }

    private val random = SecureRandom()
    private val _code = MutableStateFlow(newCode())
    val code: StateFlow<String> = _code
    @Volatile var port = 0
        private set
    @Volatile private var server: ServerSocket? = null
    private val wrong = AtomicInteger()

    /** Starts listening (first free port of 8765–8775); false when none is free. */
    fun start(): Boolean {
        if (server != null) return true
        val ss = (8765..8775).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return false
        server = ss
        port = ss.localPort
        thread(name = "phone-link", isDaemon = true) {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use { handle(it) } } }
            }
        }
        return true
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** "http://192.168.1.9:8765", or null when the TV isn't on a network. */
    fun address(): String? = localAddress()?.let { "http://$it:$port" }

    /** The address with the code, for the QR code (scanning fills the code in). */
    fun link(): String? = address()?.let { "$it/?code=${code.value}" }

    private fun newCode() = "%04d".format(random.nextInt(10_000))

    private fun handle(socket: Socket) {
        socket.soTimeout = 20_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val request = readLine(input)?.split(' ') ?: return
        val method = request.getOrNull(0).orEmpty()
        val path = request.getOrNull(1).orEmpty().substringBefore('?')
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) length = line.substring(colon + 1).trim().toIntOrNull() ?: 0
        }
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page())
            method == "POST" && path == "/send" -> {
                if (length !in 0..MAX_BODY) return respond(out, 413, JSON, """{"ok":false}""")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val fields = parseForm(String(body, 0, read, Charsets.UTF_8))
                if (!MessageDigest.isEqual(fields["code"].orEmpty().trim().toByteArray(), code.value.toByteArray())) {
                    if (wrong.incrementAndGet() >= MAX_WRONG) { wrong.set(0); _code.value = newCode() }
                    return respond(out, 403, JSON, """{"ok":false}""")
                }
                wrong.set(0)
                onReceive(fields - "code")
                respond(out, 200, JSON, """{"ok":true}""")
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8_192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    /** The phone's page, in the app's language: the code, then the playlist (or keys) fields, then Send. */
    private fun page(): String {
        fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c").replace(">", "\\u003e") + "\""
        fun field(label: String, name: String, type: String = "text", hint: String = "") =
            """<label for="$name">${h(label)}</label><input id="$name" name="$name" type="$type" placeholder="${h(hint)}" autocomplete="off" autocapitalize="off" spellcheck="false">"""
        val fields = when (form) {
            Form.PLAYLIST -> """
                <div class="tabs" role="radiogroup">
                  <label><input type="radio" name="type" value="xtream" checked><span>${h(t("Xtream Codes"))}</span></label>
                  <label><input type="radio" name="type" value="m3u"><span>${h(t("M3U link"))}</span></label>
                  <label><input type="radio" name="type" value="file"><span>${h(t("M3U file"))}</span></label>
                </div>
                <div id="xtream">${field(t("Server"), "server", "url", "http://provider.example:8080")}${field(t("Username"), "username")}${field(t("Password"), "password", "password")}</div>
                <div id="m3u" class="hide">${field(t("Playlist address"), "url", "url", "http://…/playlist.m3u")}</div>
                <div id="file" class="hide"><label for="m3ufile">${h(t("M3U file"))}</label><input id="m3ufile" type="file" accept=".m3u,.m3u8,.txt"></div>
                ${field(t("Name (optional)"), "name")}
            """
            Form.KEYS -> field("TMDB", "tmdb") + field("OMDb", "omdb") + field("OpenSubtitles", "opensubtitles")
        }
        val intro = if (form == Form.PLAYLIST) t("Send a playlist to your TV: your provider's login, a playlist link or an M3U file.")
            else t("Send your API keys to your TV. Fields you leave empty stay as they are.")
        return """<!doctype html><html lang="${h(I18n.current)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tiviate</title><style>
:root{color-scheme:dark}body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0b1220;color:#e8eef7}
main{max-width:520px;margin:0 auto;padding:28px 16px 48px}h1{font-size:24px;margin:0 0 6px}p{color:#9fb0c6;margin:0 0 18px;line-height:1.5}
label{display:block;font-size:14px;color:#9fb0c6;margin:16px 0 6px}
input{width:100%;box-sizing:border-box;padding:13px;border-radius:10px;border:1px solid #2a3a52;background:#121c2e;color:#e8eef7;font-size:16px}
input:focus{outline:2px solid #3fd0e0;border-color:transparent}#code{font-size:24px;letter-spacing:6px;max-width:160px}
.tabs{display:flex;gap:8px;margin-top:20px;flex-wrap:wrap}.tabs label{margin:0;padding:10px 14px;border-radius:999px;background:#121c2e;border:1px solid #2a3a52;color:#e8eef7}
.tabs input{position:absolute;opacity:0;width:1px}.tabs label:has(input:checked){border-color:#3fd0e0;color:#3fd0e0}
button{margin-top:24px;width:100%;padding:15px;border:0;border-radius:12px;background:#3fd0e0;color:#08131f;font-size:17px;font-weight:600}
#status{margin-top:16px;min-height:24px;line-height:1.5}.hide{display:none}
</style></head><body><main><h1>Tiviate</h1><p>${h(intro)}</p>
<form id="f">${field(t("Code shown on the TV"), "code", "text")}$fields
<button type="submit">${h(t("Send to the TV"))}</button><div id="status" role="status"></div></form></main>
<script>
const $ = id => document.getElementById(id);
const q = new URLSearchParams(location.search); if (q.get('code')) $('code').value = q.get('code');
$('code').setAttribute('inputmode', 'numeric'); $('code').setAttribute('maxlength', '4');
document.querySelectorAll('input[name=type]').forEach(r => r.addEventListener('change', () => {
  for (const id of ['xtream', 'm3u', 'file']) $(id).classList.toggle('hide', id !== r.value);
}));
$('f').addEventListener('submit', async e => {
  e.preventDefault();
  const status = $('status');
  const data = new URLSearchParams(new FormData(e.target));
  if (data.get('type') === 'file') {
    const file = $('m3ufile').files[0];
    if (!file) { status.textContent = ${js(t("Choose an M3U file first."))}; return; }
    data.set('m3u', await file.text()); data.set('filename', file.name);
  }
  status.textContent = ${js(t("Sending…"))};
  try {
    const r = await fetch('/send', { method: 'POST', body: data });
    status.textContent = r.ok ? ${js(t("Sent. Look at your TV."))} : r.status === 403 ? ${js(t("Wrong code. Check the code on the TV."))} : ${js(t("The TV couldn't take it. Try again."))};
  } catch (x) { status.textContent = ${js(t("Couldn't reach the TV. Is the phone on the same Wi-Fi?"))}; }
});
</script></body></html>"""
    }

    companion object {
        private const val JSON = "application/json"
        /** Big enough for an M3U with tens of thousands of channels. */
        private const val MAX_BODY = 40 * 1024 * 1024
        private const val MAX_WRONG = 5

        fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val i = pair.indexOf('=')
            val key = if (i < 0) pair else pair.substring(0, i)
            val value = if (i < 0) "" else pair.substring(i + 1)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
