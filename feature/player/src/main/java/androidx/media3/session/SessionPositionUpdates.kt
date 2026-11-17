package androidx.media3.session

/**
 * Overtime: how often the session sends the position to the player screen. Media3 keeps the setter
 * package-private (default 3 s); this file sits in its package to reach it.
 *
 * Between updates the screen moves the position on by itself but keeps the length as it was. For a
 * recorded live channel the length grows too, so with 3 s updates a rewound bar's knob drifted right
 * and jumped back on every update.
 */
fun MediaSession.setPositionUpdateDelay(delayMs: Long) = setSessionPositionUpdateDelayMs(delayMs)
