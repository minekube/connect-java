package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.identity.EndpointNameSource
import java.io.IOException
import java.security.SecureRandom
import java.util.random.RandomGenerator
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class RandomEndpointNameSource(
    client: OkHttpClient,
    private val url: HttpUrl = DEFAULT_URL,
    timeout: Duration = 5.seconds,
    private val random: RandomGenerator = SecureRandom(),
) : EndpointNameSource {
    private val client = client.newBuilder()
        .callTimeout(timeout.toJavaDuration())
        .build()

    override suspend fun create(): String =
        fetch().fold(
            ifLeft = { fallback() },
            ifRight = { remote ->
                remote.takeIf(ENDPOINT_PATTERN::matches) ?: fallback()
            },
        )

    private suspend fun fetch(): Either<Throwable, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(Either.Left(e))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = Either.catch {
                            response.use {
                                if (it.code != 200) {
                                    throw IOException("Random endpoint service returned non-200")
                                }
                                it.body?.string()
                                    ?: throw IOException("Random endpoint service returned no body")
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )
        }

    private fun fallback(): String = buildString(FALLBACK_LENGTH) {
        repeat(FALLBACK_LENGTH) {
            append('a' + random.nextInt(26))
        }
    }

    private companion object {
        val DEFAULT_URL: HttpUrl = "https://randomname.minekube.net".toHttpUrl()
        val ENDPOINT_PATTERN = Regex("^[a-z0-9][a-z0-9-]{2,62}$")
        const val FALLBACK_LENGTH = 5
    }
}
