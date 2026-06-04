package com.neilturner.aerialviews.services

import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

class MessageFetchService(
    private val onMessageReceived: (MessageEvent) -> Unit,
) {
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    fun start() {
        val url = GeneralPrefs.messageFetchUrl
        if (url.isBlank()) {
            Timber.i("MessageFetchService: no fetch URL configured, skipping")
            return
        }

        val intervalMinutes = GeneralPrefs.messageFetchIntervalMinutes.toLongOrNull() ?: 60L
        Timber.i("MessageFetchService: starting, URL=$url, interval=${intervalMinutes}m")

        fetchScope.launch {
            while (true) {
                try {
                    fetchAndApply(url)
                } catch (e: Exception) {
                    Timber.e(e, "MessageFetchService: fetch failed")
                }
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    fun stop() {
        Timber.i("MessageFetchService: stopping")
        fetchScope.cancel()
    }

    private fun fetchAndApply(url: String) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        response.use {
            if (!it.isSuccessful) {
                Timber.w("MessageFetchService: HTTP ${it.code} from $url")
                return
            }

            val body = it.body.string()
            val fetchResponse = json.decodeFromString<FetchResponse>(body)

            for (slot in fetchResponse.slots) {
                if (slot.slot !in 1..4) continue

                val type = OverlayType.entries.firstOrNull { e -> e.name == "MESSAGE${slot.slot}" } ?: continue
                val event =
                    MessageEvent(
                        type = type,
                        text = slot.text,
                        textSize = slot.textSize,
                        textWeight = slot.textWeight,
                    )
                onMessageReceived(event)
            }

            Timber.i("MessageFetchService: applied ${fetchResponse.slots.size} slots from $url")
        }
    }
}

@Serializable
data class FetchSlot(
    val slot: Int,
    val text: String,
    val textSize: Int? = null,
    val textWeight: Int? = null,
)

@Serializable
data class FetchResponse(
    val slots: List<FetchSlot>,
)
