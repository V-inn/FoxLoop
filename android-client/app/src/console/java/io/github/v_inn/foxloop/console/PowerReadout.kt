package io.github.v_inn.foxloop.console

/**
 * The status line's words. Pure, so the JVM tests can hold it to the bit
 * table.
 *
 * The line exists for one question: is the machine short of power? A phone on
 * the cable can draw from the same USB budget as a Pi's disk -- the Pi 4's
 * ports share 1.2 A in total -- and an under-powered Pi shows it as storage
 * dropping out long before anything says "power". The Pi's own throttled flags
 * are the verdict that matters.
 *
 * The phone's charge current used to sit beside it, and was taken out after the
 * first session on hardware: Android's `isCharging` is stricter than the battery
 * icon (a low-current port reads "not charging" under a lightning bolt), so the
 * line contradicted the phone's own status bar and answered nothing the
 * throttled flags do not.
 */
internal object PowerReadout {
    // `vcgencmd get_throttled` bits. The low half is "right now", the high half
    // "has happened since boot".
    private const val UNDER_VOLTAGE_NOW = 1 shl 0
    private const val FREQ_CAPPED_NOW = 1 shl 1
    private const val THROTTLED_NOW = 1 shl 2
    private const val SOFT_TEMP_LIMIT_NOW = 1 shl 3
    private const val UNDER_VOLTAGE_SINCE_BOOT = 1 shl 16
    private const val THROTTLED_SINCE_BOOT = 1 shl 18

    enum class Level { Fine, Warn, Alert }

    data class Reading(val text: String, val level: Level)

    /** Null on a machine that cannot report it, rather than a guess. */
    fun power(throttled: Int): Reading? = when {
        throttled == ConsoleProtocol.THROTTLED_UNKNOWN -> null
        throttled and UNDER_VOLTAGE_NOW != 0 -> Reading("under-voltage now", Level.Alert)
        throttled and (THROTTLED_NOW or FREQ_CAPPED_NOW or SOFT_TEMP_LIMIT_NOW) != 0 ->
            Reading("throttled now", Level.Alert)
        throttled and UNDER_VOLTAGE_SINCE_BOOT != 0 -> Reading("under-voltage since boot", Level.Warn)
        throttled and THROTTLED_SINCE_BOOT != 0 -> Reading("throttled since boot", Level.Warn)
        else -> Reading("power ok", Level.Fine)
    }

    fun temperature(milliDegrees: Int): String? =
        if (milliDegrees == ConsoleProtocol.TEMP_UNKNOWN) null
        else "%.1f °C".format(java.util.Locale.ROOT, milliDegrees / 1000.0)
}
