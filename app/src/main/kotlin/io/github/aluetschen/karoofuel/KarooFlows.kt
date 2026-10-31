package io.github.aluetschen.karoofuel

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Live values of a Karoo data type (e.g. power) as a Flow. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val id = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySend(event.state)
    }
    awaitClose { removeConsumer(id) }
}

/** Karoo events (ride state, profile …) as a Flow. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val id = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(id) }
}
