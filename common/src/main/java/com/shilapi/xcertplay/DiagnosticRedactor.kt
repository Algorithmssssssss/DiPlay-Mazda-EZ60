package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b|\\b[0-9a-f]{8}-[0-9a-f]{16}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    // A vehicle identification number: 17 of its letters (no I, O or Q) and digits, with both. The EZ-60's voice
    // assistant writes one into a setting the probe records (tinnove_wecarspeech_session_speech_id).
    private val vin = Regex("(?<![A-HJ-NPR-Z0-9])(?=[A-HJ-NPR-Z0-9]{0,16}[A-HJ-NPR-Z])(?=[A-HJ-NPR-Z0-9]{0,16}[0-9])" +
        "[A-HJ-NPR-Z0-9]{17}(?![A-HJ-NPR-Z0-9])")

    /** Cheap check for lines [redact] always rejects, so a queue can refuse them before storing. */
    fun neverExported(line: String): Boolean =
        line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')

    fun redact(line: String): String? {
        if (neverExported(line)) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").replace(vin, "[vin]").take(700)
    }

    /** Masks addresses and long identifiers but keeps the line, for EZ-60 probe facts that carry no secrets. */
    fun mask(line: String): String = line.replace(mac, "[address]").replace(identifier, "[identifier]")
        .replace(address, "[ip]").replace(ipv6, "[ip]").replace(vin, "[vin]")
}
