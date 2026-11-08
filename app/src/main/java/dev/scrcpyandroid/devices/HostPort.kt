package dev.scrcpyandroid.devices

data class HostPort(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

const val DEFAULT_ADB_PORT = 5555

/** Parses `host`, `host:port`, `[v6]` or `[v6]:port`. Port defaults to 5555 and must be 1..65535. */
fun parseHostPort(input: String): HostPort? {
    val s = input.trim()
    if (s.isEmpty()) return null
    val host: String
    val portText: String?
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0) return null
        host = s.substring(1, end)
        val rest = s.substring(end + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
    } else {
        val idx = s.lastIndexOf(':')
        if (idx < 0) {
            host = s
            portText = null
        } else {
            if (s.indexOf(':') != idx) return null // bare IPv6 is ambiguous; require brackets
            host = s.substring(0, idx)
            portText = s.substring(idx + 1)
        }
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    val port = if (portText == null) DEFAULT_ADB_PORT else portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return HostPort(host, port)
}
