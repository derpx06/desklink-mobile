package org.desklink.mobile.protocol.desklinkv9

/**
 * Active DeskLink Protocol v9 identifiers.
 *
 * The legacy KDE Connect-compatible values remain isolated in
 * legacykdeconnectv8 for historical fixtures only. They are not sent by the
 * active DeskLink application.
 */
object DeskLinkProtocol {
    const val PROTOCOL_VERSION = 9
    const val MDNS_SERVICE_TYPE = "_desklink._udp"

    const val PACKET_TYPE_IDENTITY = "desklink.identity"
    const val PACKET_TYPE_PAIR = "desklink.pair"
    /** QR pairing proof (phone → desktop), LAN bootstrap only. Must match `packet.rs`. */
    const val PACKET_TYPE_PAIR_QR = "desklink.pair.qr"

    /**
     * Liveness probe for the pinned-TLS bootstrap link.
     *
     * **The desktop initiates; this device answers.** Deliberately asymmetric.
     * If both ends probed only when their *inbound* side went quiet they would
     * deadlock into silence: each other's probe would refresh the listener, so
     * neither would ever send, and each would then declare the other dead.
     * One initiator and one responder removes that entirely, and the mains-
     * powered side is the right one to carry the timer.
     *
     * Answering also keeps `LanLink`'s 120s idle reaper from tearing down a
     * link the desktop still believes in — which is one half of the livelock
     * this exists to end.
     */
    const val PACKET_TYPE_HEARTBEAT = "desklink.heartbeat"
    const val PACKET_TYPE_PING = "desklink.ping"
    const val PACKET_TYPE_MOUSEPAD_REQUEST = "desklink.mousepad.request"
    const val PACKET_TYPE_MOUSEPAD_ECHO = "desklink.mousepad.echo"
    const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE = "desklink.mousepad.keyboardstate"
    const val PACKET_TYPE_SHARE_REQUEST = "desklink.share.request"
    const val PACKET_TYPE_SHARE_REQUEST_UPDATE = "desklink.share.request.update"
    const val PACKET_TYPE_CLIPBOARD = "desklink.clipboard"
    const val PACKET_TYPE_CLIPBOARD_CONNECT = "desklink.clipboard.connect"
    const val PACKET_TYPE_LOCK = "desklink.lock"
    const val PACKET_TYPE_LOCK_REQUEST = "desklink.lock.request"
    const val PACKET_TYPE_FINDMYPHONE_REQUEST = "desklink.findmyphone.request"
    const val PACKET_TYPE_MPRIS = "desklink.mpris"
    const val PACKET_TYPE_MPRIS_REQUEST = "desklink.mpris.request"
    const val PACKET_TYPE_BATTERY = "desklink.battery"
    const val PACKET_TYPE_NOTIFICATION = "desklink.notification"
    const val PACKET_TYPE_NOTIFICATION_REQUEST = "desklink.notification.request"
    const val PACKET_TYPE_NOTIFICATION_CANCEL = "desklink.notification.cancel"
    const val PACKET_TYPE_NOTIFICATION_REPLY = "desklink.notification.reply"
    const val PACKET_TYPE_NOTIFICATION_ACTION = "desklink.notification.action"
    const val PACKET_TYPE_SYSTEMVOLUME = "desklink.systemvolume"
    const val PACKET_TYPE_SYSTEMVOLUME_REQUEST = "desklink.systemvolume.request"
    const val PACKET_TYPE_RUNCOMMAND = "desklink.runcommand"
    const val PACKET_TYPE_RUNCOMMAND_REQUEST = "desklink.runcommand.request"
    const val PACKET_TYPE_SCREEN_REQUEST = "desklink.screen.request"
    const val PACKET_TYPE_SCREEN_READY = "desklink.screen.ready"
    const val PACKET_TYPE_SCREEN_FRAME = "desklink.screen.frame"
    const val PACKET_TYPE_SCREEN_STOP = "desklink.screen.stop"
    const val PACKET_TYPE_SCREEN_ERROR = "desklink.screen.error"
    const val PACKET_TYPE_PRESENTER = "desklink.presenter"
    const val PACKET_TYPE_CONTACTS_REQUEST = "desklink.contacts.request"
    const val PACKET_TYPE_SMS_REQUEST = "desklink.sms.request"
    const val PACKET_TYPE_TELEPHONY_REQUEST = "desklink.telephony.request"
    const val PACKET_TYPE_CONNECTIVITY_REPORT = "desklink.connectivity_report"
    const val PACKET_TYPE_WEBRTC_SIGNAL_V1 = "desklink.webrtc.signal.v1"
    const val PACKET_TYPE_SHARE_INPUT_DEVICES = "desklink.shareinputdevices"
    const val PACKET_TYPE_SHARE_INPUT_DEVICES_REQUEST = "desklink.shareinputdevices.request"
    const val PACKET_TYPE_DIGITIZER_SESSION = "desklink.digitizer.session"
    const val PACKET_TYPE_DIGITIZER = "desklink.digitizer"

    /** Desk → phone: the desk's own settings, on feature-ready and on every change. */
    const val PACKET_TYPE_SETTINGS = "desklink.settings"

    /** Phone → desk: `{set: {key: bool}}` for the allow-listed desk settings. */
    const val PACKET_TYPE_SETTINGS_REQUEST = "desklink.settings.request"

    /** Both ways: the desk's unlock nonce (`{nonce, reason}`), or the phone asking for one (`{ask: true}`). */
    const val PACKET_TYPE_UNLOCK_REQUEST = "desklink.unlock.request"

    /** Desk → phone: continue this terminal (`{sessionId, title, path}`) after Lock & Hand Off. */
    const val PACKET_TYPE_HANDOFF = "desklink.handoff"

    /** Both ways (F6): synced snippets, `{op: "index"|"fetch"|"items", ...}`. */
    const val PACKET_TYPE_SNIPPETS = "desklink.snippets"

    /** Both ways (F6): synced notes, `{op: "index"|"fetch"|"items", ...}`. */
    const val PACKET_TYPE_NOTES = "desklink.notes"

    /** Both ways (F7): automation rules, events and actions, `{op: "index"|"fetch"|"items"|"event"|"do"|"done", ...}`. */
    const val PACKET_TYPE_AUTOMATIONS = "desklink.automations"

    /** Both ways (F8): synced folder pairs and manifests, `{op: "offer"|"accept"|"decline"|"remove"|"pairs"|"manifest"|"request", ...}`. */
    const val PACKET_TYPE_FOLDERS = "desklink.folders"
    const val PACKET_TYPE_CONTACTS_REQUEST_ALL_UIDS_TIMESTAMPS =
        "desklink.contacts.request_all_uids_timestamps"
    const val PACKET_TYPE_CONTACTS_REQUEST_VCARDS_BY_UIDS =
        "desklink.contacts.request_vcards_by_uid"
    const val PACKET_TYPE_CONTACTS_RESPONSE_UIDS_TIMESTAMPS =
        "desklink.contacts.response_uids_timestamps"
    const val PACKET_TYPE_CONTACTS_RESPONSE_VCARDS = "desklink.contacts.response_vcards"
    const val PACKET_TYPE_SMS_MESSAGE = "desklink.sms.messages"
    const val PACKET_TYPE_SMS_REQUEST_CONVERSATIONS = "desklink.sms.request_conversations"
    const val PACKET_TYPE_SMS_REQUEST_CONVERSATION = "desklink.sms.request_conversation"
    const val PACKET_TYPE_SMS_REQUEST_ATTACHMENT = "desklink.sms.request_attachment"
    const val PACKET_TYPE_SMS_ATTACHMENT_FILE = "desklink.sms.attachment_file"
    const val PACKET_TYPE_TELEPHONY = "desklink.telephony"
    const val PACKET_TYPE_TELEPHONY_REQUEST_MUTE = "desklink.telephony.request_mute"
}
