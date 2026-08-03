package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.providers.openai.ResponseStreamErrorException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTransportRetryTest {
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
}
