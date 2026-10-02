package com.cleo.cleos.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * [request] sent, and its answer [read], on OkHttp's own threads, given up the moment the caller
 * is cancelled: the call is cancelled, which closes the connection under a read that is still
 * waiting. A plain execute() holds its caller until the answer comes, however long that takes: a
 * call hung up while a voice service was still answering stayed on the screen until it had.
 * What [read] throws comes out here; a network failure, as IOException.
 */
suspend fun <T> OkHttpClient.fetch(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resumeWith(runCatching { response.use(read) })
            }
        },
    )
}
