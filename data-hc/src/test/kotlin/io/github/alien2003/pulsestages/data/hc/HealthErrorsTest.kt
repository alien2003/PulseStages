package io.github.alien2003.pulsestages.data.hc

import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Stands in for the platform `android.health.connect.HealthConnectException` (unusable on the JVM). */
private class FakePlatformException(
    val code: Int,
) : RuntimeException("platform error $code")

private val fakeCodes: (Throwable) -> Int? = { (it as? FakePlatformException)?.code }

private fun map(
    error: Throwable,
    available: Boolean = true,
): HealthDataException = HealthExceptionMapper.map("op", error, fakeCodes) { available }

class HealthExceptionMapperTest {
    @Test
    fun `security exceptions mean a missing permission`() {
        assertInstanceOf(HealthDataException.PermissionDenied::class.java, map(SecurityException("no READ_SLEEP")))
    }

    @Test
    fun `rate limit and data sync in progress wrapped in IllegalStateException are quota errors`() {
        val rateLimited = map(IllegalStateException(FakePlatformException(PlatformErrorCodes.RATE_LIMIT_EXCEEDED)))
        assertInstanceOf(HealthDataException.QuotaExceeded::class.java, rateLimited)
        assertTrue(rateLimited.message.orEmpty().contains("platform error code 7"))
        assertInstanceOf(
            HealthDataException.QuotaExceeded::class.java,
            map(IllegalStateException(FakePlatformException(PlatformErrorCodes.DATA_SYNC_IN_PROGRESS))),
        )
        assertEquals(7, PlatformErrorCodes.RATE_LIMIT_EXCEEDED)
        assertEquals(8, PlatformErrorCodes.DATA_SYNC_IN_PROGRESS)
    }

    @Test
    fun `the platform code is found through a stack-trace-recovery copy of the exception`() {
        // kotlinx-coroutines (debug mode / assertions) rethrows a copy of an exception whose cause is the original.
        val recovered = IllegalStateException("copy", IllegalStateException(FakePlatformException(PlatformErrorCodes.RATE_LIMIT_EXCEEDED)))
        assertInstanceOf(HealthDataException.QuotaExceeded::class.java, map(recovered))
        assertTrue(
            HealthExceptionMapper.isCausedByRemoteException(
                HealthDataException.Transient("copy", HealthDataException.Transient("x", RemoteException())),
            ),
        )
        assertEquals(false, HealthExceptionMapper.isCausedByRemoteException(HealthDataException.Transient("x", IOException())))
    }

    @Test
    fun `other IllegalStateExceptions are transient while Health Connect is available, unavailable otherwise`() {
        assertInstanceOf(HealthDataException.Transient::class.java, map(IllegalStateException(FakePlatformException(2))))
        assertInstanceOf(HealthDataException.Transient::class.java, map(IllegalStateException("internal")))
        assertInstanceOf(
            HealthDataException.Unavailable::class.java,
            map(IllegalStateException("Service not available"), available = false),
        )
        assertInstanceOf(
            HealthDataException.Unavailable::class.java,
            map(IllegalStateException(FakePlatformException(PlatformErrorCodes.UNSUPPORTED_OPERATION))),
        )
    }

    @Test
    fun `IPC and IO errors are transient`() {
        assertInstanceOf(HealthDataException.Transient::class.java, map(RemoteException()))
        assertInstanceOf(HealthDataException.Transient::class.java, map(IOException("disk")))
        assertInstanceOf(HealthDataException.Transient::class.java, map(RuntimeException("rethrowFromSystemServer")))
    }

    @Test
    fun `invalid arguments and unsupported operations`() {
        assertInstanceOf(HealthDataException.InvalidData::class.java, map(IllegalArgumentException("stages overlap")))
        assertInstanceOf(HealthDataException.InvalidData::class.java, map(NumberFormatException("For input string: \"\"")))
        assertInstanceOf(HealthDataException.Unavailable::class.java, map(UnsupportedOperationException("SDK version too low")))
    }

    @Test
    fun `a raw platform exception is mapped by its code and HealthDataExceptions pass through`() {
        assertInstanceOf(HealthDataException.QuotaExceeded::class.java, map(FakePlatformException(PlatformErrorCodes.RATE_LIMIT_EXCEEDED)))
        assertInstanceOf(HealthDataException.PermissionDenied::class.java, map(FakePlatformException(PlatformErrorCodes.SECURITY)))
        assertInstanceOf(HealthDataException.InvalidData::class.java, map(FakePlatformException(PlatformErrorCodes.INVALID_ARGUMENT)))
        val own = HealthDataException.Transient("mine")
        assertSame(own, map(own))
    }

    @Test
    fun `the platform code is never read below API 34`() {
        // Build.VERSION.SDK_INT is 0 in local unit tests: PlatformErrorCodes.of must not touch the platform class.
        assertEquals(null, PlatformErrorCodes.of(IllegalStateException("x")))
    }

    @Test
    fun `mapHealthErrors rethrows cancellation unchanged and maps everything else`() =
        runTest {
            val cancellation = CancellationException("stop")
            val thrown = assertThrows<CancellationException> { mapHealthErrors({ map(it) }) { throw cancellation } }
            assertSame(cancellation, thrown)
            assertThrows<HealthDataException.PermissionDenied> { mapHealthErrors({ map(it) }) { throw SecurityException("x") } }
            assertEquals(42, mapHealthErrors({ map(it) }) { 42 })
        }
}

class RetryTest {
    private class RecordingSleeper : Sleeper {
        val waits = mutableListOf<Long>()

        override suspend fun sleep(millis: Long) {
            waits += millis
        }
    }

    @Test
    fun `retryable errors are retried with exponential backoff 1, 2, 4, 8, 16 s and then rethrown`() =
        runTest {
            val sleeper = RecordingSleeper()
            var attempts = 0
            val error =
                assertThrows<HealthDataException.QuotaExceeded> {
                    retryHealthCall(RetryPolicy.DEFAULT, sleeper) {
                        attempts++
                        throw HealthDataException.QuotaExceeded("quota")
                    }
                }
            assertEquals("quota", error.message)
            assertEquals(6, attempts)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), sleeper.waits)
        }

    @Test
    fun `a call that recovers returns its value`() =
        runTest {
            val sleeper = RecordingSleeper()
            var attempts = 0
            val retries = mutableListOf<Int>()
            val value =
                retryHealthCall(RetryPolicy.DEFAULT, sleeper, onRetry = { retry, _, _ -> retries += retry }) {
                    attempts++
                    if (attempts < 3) throw HealthDataException.Transient("ipc")
                    "ok"
                }
            assertEquals("ok", value)
            assertEquals(listOf(1_000L, 2_000L), sleeper.waits)
            assertEquals(listOf(1, 2), retries)
        }

    @Test
    fun `non-retryable errors are not retried`() =
        runTest {
            val sleeper = RecordingSleeper()
            var attempts = 0
            assertThrows<HealthDataException.PermissionDenied> {
                retryHealthCall(RetryPolicy.DEFAULT, sleeper) {
                    attempts++
                    throw HealthDataException.PermissionDenied("revoked")
                }
            }
            assertThrows<HealthDataException.InvalidData> {
                retryHealthCall(RetryPolicy.DEFAULT, sleeper) { throw HealthDataException.InvalidData("bad") }
            }
            assertEquals(1, attempts)
            assertTrue(sleeper.waits.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime
    @Test
    fun `the backoff runs in virtual time with DelaySleeper`() =
        runTest {
            val result =
                async {
                    runCatching {
                        retryHealthCall(RetryPolicy.DEFAULT, DelaySleeper) { throw HealthDataException.Transient("down") }
                    }
                }.await()
            assertInstanceOf(HealthDataException.Transient::class.java, result.exceptionOrNull())
            assertEquals(31_000L, currentTime)
        }

    @Test
    fun `retryability`() {
        assertTrue(HealthDataException.QuotaExceeded("q").isRetryable)
        assertTrue(HealthDataException.Transient("t").isRetryable)
        assertEquals(false, HealthDataException.PermissionDenied("p").isRetryable)
        assertEquals(false, HealthDataException.Unavailable("u").isRetryable)
        assertEquals(false, HealthDataException.InvalidData("i").isRetryable)
        assertThrows<IllegalArgumentException> { RetryPolicy(listOf(-1L)) }
    }
}
