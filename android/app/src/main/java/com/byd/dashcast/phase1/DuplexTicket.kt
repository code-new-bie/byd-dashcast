package com.byd.dashcast.phase1

import java.security.SecureRandom

/** Per-session Binder rendezvous, never a persistent privilege or a logged credential. */
internal object DuplexTicket {
    private const val PREFIX = "duplex:"
    @JvmStatic fun create(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @JvmStatic fun valid(value: String?): Boolean = value != null &&
        value.length == 48 && value.all { it in '0'..'9' || it in 'a'..'f' }
    @JvmStatic fun argument(value: String): String {
        require(valid(value))
        return PREFIX + value
    }
    @JvmStatic fun read(args: Array<String>): String? = args.lastOrNull()?.takeIf { it.startsWith(PREFIX) }
        ?.substring(PREFIX.length)?.also { require(valid(it)) { "APP_DUPLEX_TICKET_INVALID" } }
    @JvmStatic fun without(args: Array<String>): Array<String> =
        if (read(args) != null) args.copyOfRange(0, args.size - 1) else args
}
