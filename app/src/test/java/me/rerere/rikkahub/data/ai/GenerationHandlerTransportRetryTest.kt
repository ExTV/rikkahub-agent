package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTransportRetryTest {
    @Test
    fun `does not report an empty stream once a chunk arrived, even without parseable parts`() {
        assertFalse(shouldReportEmptyGenerationStream(receivedAnyChunk = true))
    }

    @Test
    fun `still reports an empty stream when zero chunks arrived`() {
        assertTrue(shouldReportEmptyGenerationStream(receivedAnyChunk = false))
    }

    @Test
    fun `retries an abort before any model output`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IOException("Software caused connection abort"),
                retryAttempt = 0,
                maxRetries = 2,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not replay a stream after meaningful output`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = IOException("connection reset"),
                retryAttempt = 0,
                maxRetries = 2,
                receivedMeaningfulOutput = true,
            )
        )
    }

    @Test
    fun `does not retry after the configured attempts`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = IOException("connection reset"),
                retryAttempt = 2,
                maxRetries = 2,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `retries non network failures`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IllegalStateException("response body could not be decoded"),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry user cancellation`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = CancellationException("Generation cancelled by user"),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry a provider business error`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = ResponseStreamErrorException(
                    code = "context_length_exceeded",
                    message = "Response API response.failed [context_length_exceeded]: maximum context length",
                ),
                retryAttempt = 0,
                maxRetries = 10,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry deterministic 4xx client errors`() {
        for (statusCode in listOf(400, 401, 403, 404)) {
            assertFalse(
                "status $statusCode should not retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("client error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `retries the 4xx statuses that signal a transient condition`() {
        for (statusCode in listOf(408, 409, 425, 429)) {
            assertTrue(
                "status $statusCode should retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("transient client error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `retries 5xx server errors`() {
        for (statusCode in listOf(500, 503)) {
            assertTrue(
                "status $statusCode should retry",
                shouldRetryGenerationStreamFailure(
                    failure = HttpException("server error", statusCode = statusCode),
                    retryAttempt = 0,
                    maxRetries = 5,
                    receivedMeaningfulOutput = false,
                )
            )
        }
    }

    @Test
    fun `retries a failure with no status code attached`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("no status known", statusCode = null),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `a wrapped deterministic 4xx is still found through the cause chain`() {
        val wrapped = RuntimeException(
            "wrapper",
            HttpException("client error", statusCode = 400),
        )
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = wrapped,
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry a 429 resource exhausted quota error`() {
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = HttpException(
                    "RESOURCE_EXHAUSTED: Quota exceeded",
                    statusCode = 429,
                ),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `does not retry a 429 whose cause chain reports resource has been exhausted`() {
        val wrapped = RuntimeException(
            "wrapper",
            HttpException("The resource has been exhausted", statusCode = 429),
        )
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = wrapped,
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `still retries a plain 429 rate limit message`() {
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = HttpException("Rate limit exceeded, please slow down", statusCode = 429),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }

    @Test
    fun `a deterministic 4xx still yields to max-retries and cancellation precedence`() {
        assertFalse(
            "receivedMeaningfulOutput still wins",
            shouldRetryGenerationStreamFailure(
                failure = HttpException("client error", statusCode = 429),
                retryAttempt = 0,
                maxRetries = 5,
                receivedMeaningfulOutput = true,
            )
        )
        assertFalse(
            "max retries still wins",
            shouldRetryGenerationStreamFailure(
                failure = HttpException("client error", statusCode = 429),
                retryAttempt = 5,
                maxRetries = 5,
                receivedMeaningfulOutput = false,
            )
        )
    }
}
