package dev.anilbeesetti.nextplayer.feature.player.timeshift

import org.junit.Assert

// kotlin.test-style assertions, so these tests read the same as the Windows originals.

internal fun assertEquals(expected: Any?, actual: Any?, message: String? = null) =
    Assert.assertEquals(
        message,
        expected,
        actual,
    )

internal fun assertTrue(condition: Boolean, message: String? = null) = Assert.assertTrue(
    message,
    condition,
)

internal fun assertFalse(condition: Boolean, message: String? = null) = Assert.assertFalse(
    message,
    condition,
)

internal fun assertContentEquals(expected: ByteArray, actual: ByteArray) = Assert.assertArrayEquals(
    expected,
    actual,
)

internal inline fun <reified T> assertIs(value: Any?, message: String? = null) {
    Assert.assertTrue(
        message ?: "expected ${T::class.simpleName}, was $value",
        value is T,
    )
}
