package app.tiviate.data

import app.tiviate.data.db.ChannelEntity
import java.security.MessageDigest

/** Parental control: a 4-digit PIN that locks chosen channels, groups and the settings. */
object Parental {
    const val PIN_LENGTH = 4

    /** What is stored instead of the PIN itself. */
    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("tiviate-pin:$pin".toByteArray()).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    fun matches(settings: AppSettings, pin: String): Boolean = settings.pinHash != null && settings.pinHash == hash(pin)

    fun channelKey(channel: ChannelEntity) = "${channel.playlistId}:${channel.stableKey}"
    fun groupKey(playlistId: Long, group: String) = "$playlistId|$group"

    /** The channel, or its group, is locked (and a PIN is set). */
    fun locked(settings: AppSettings, channel: ChannelEntity): Boolean {
        if (settings.pinHash == null) return false
        return channelKey(channel) in settings.lockedChannels ||
            (channel.groupName != null && groupKey(channel.playlistId, channel.groupName) in settings.lockedGroups)
    }
}
