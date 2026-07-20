package org.desklink.mobile.protocol.legacykdeconnectv8

/**
 * KDE Connect-compatible protocol v8 wire identifiers.
 *
 * Retained as a legacy KDE Connect-compatible wire identifier. This is not a
 * user-visible DeskLink product name. Do not change these values during
 * branding work: paired DeskLink peers serialize and discover with them.
 */
object LegacyKdeConnectV8 {
    const val PROTOCOL_VERSION = 8
    const val MDNS_SERVICE_TYPE = "_kdeconnect._udp"

    const val PACKET_TYPE_IDENTITY = "kdeconnect.identity"
    const val PACKET_TYPE_PAIR = "kdeconnect.pair"
    const val PACKET_TYPE_PING = "kdeconnect.ping"
    const val PACKET_TYPE_MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
    const val PACKET_TYPE_MOUSEPAD_ECHO = "kdeconnect.mousepad.echo"
    const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
    const val PACKET_TYPE_SHARE_REQUEST = "kdeconnect.share.request"
    const val PACKET_TYPE_SHARE_REQUEST_UPDATE = "kdeconnect.share.request.update"
    const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"
    const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
    const val PACKET_TYPE_LOCK = "kdeconnect.lock"
    const val PACKET_TYPE_LOCK_REQUEST = "kdeconnect.lock.request"
    const val PACKET_TYPE_FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
    const val PACKET_TYPE_MPRIS = "kdeconnect.mpris"
    const val PACKET_TYPE_MPRIS_REQUEST = "kdeconnect.mpris.request"
    const val PACKET_TYPE_SFTP = "kdeconnect.sftp"
    const val PACKET_TYPE_SFTP_REQUEST = "kdeconnect.sftp.request"
    const val PACKET_TYPE_BATTERY = "kdeconnect.battery"
    const val PACKET_TYPE_NOTIFICATION = "kdeconnect.notification"
    const val PACKET_TYPE_NOTIFICATION_REQUEST = "kdeconnect.notification.request"
    const val PACKET_TYPE_NOTIFICATION_REPLY = "kdeconnect.notification.reply"
    const val PACKET_TYPE_NOTIFICATION_ACTION = "kdeconnect.notification.action"
    const val PACKET_TYPE_SYSTEMVOLUME = "kdeconnect.systemvolume"
    const val PACKET_TYPE_SYSTEMVOLUME_REQUEST = "kdeconnect.systemvolume.request"
    const val PACKET_TYPE_RUNCOMMAND = "kdeconnect.runcommand"
    const val PACKET_TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
}
