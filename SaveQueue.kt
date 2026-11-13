package com.eliosnotes.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Process-wide scope: saves started when an editor closes must outlive its UI. */
object AppScope : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
}

/**
 * Runs every database write/load strictly in submission order on one worker, so an old
 * snapshot can never overwrite a newer one and a reload always sees earlier saves.
 */
object SaveQueue {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        AppScope.launch {
            for (task in channel) {
                try {
                    task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("SaveQueue", "task failed", e)
                }
            }
        }
    }

    fun submit(task: suspend () -> Unit) {
        channel.trySend(task)
    }

    suspend fun <T> run(task: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        submit {
            try {
                result.complete(task())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }
}
