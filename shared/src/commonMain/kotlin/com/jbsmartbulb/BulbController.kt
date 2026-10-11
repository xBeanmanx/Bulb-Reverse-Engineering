@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.jbsmartbulb

import com.jbsmartbulb.protocol.BluzFrame
import com.jbsmartbulb.protocol.BluzFrameDecoder
import com.jbsmartbulb.protocol.BluzKeys
import com.jbsmartbulb.protocol.BulbCommands
import com.jbsmartbulb.protocol.DeviceStatus
import com.jbsmartbulb.protocol.EqPreset
import com.jbsmartbulb.protocol.FadePreset
import com.jbsmartbulb.protocol.LampState
import com.jbsmartbulb.protocol.toHex
import com.jbsmartbulb.transport.BulbDevice
import com.jbsmartbulb.transport.BulbPlatform
import com.jbsmartbulb.transport.BulbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: BulbDevice) : ConnectionState
    data class Connected(val device: BulbDevice) : ConnectionState
    data class Failed(val device: BulbDevice, val reason: String) : ConnectionState
}

data class LogEntry(val outgoing: Boolean, val text: String)

/** Progress of handing the bulb over to Classic audio (see [BulbController.connectSpeaker]). */
sealed interface SpeakerState {
    data object Idle : SpeakerState
    data object Working : SpeakerState
    data object Connected : SpeakerState
    data class Failed(val reason: String) : SpeakerState
}

/**
 * Owns one bulb connection and replays the original app's session:
 * handshake → feature query → clock sync → lamp state query → status poll every ~400 ms.
 */
class BulbController(
    private val platform: BulbPlatform,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _lamp = MutableStateFlow(LampState.OFF)
    val lamp: StateFlow<LampState> = _lamp.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Sleep timer end, epoch millis, as set from this app (the bulb does not report it). */
    private val _sleepTimerEndsAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndsAt: StateFlow<Long?> = _sleepTimerEndsAt.asStateFlow()

    private var session: Job? = null
    private var transport: BulbTransport? = null
    private val writeLock = Mutex()
    private val statusReplies = Channel<Unit>(Channel.CONFLATED)
    private val featureReplies = Channel<Unit>(Channel.CONFLATED)
    private val lampReplies = Channel<Unit>(Channel.CONFLATED)

    /** Interval between fade colours: 70% of the colour-update rate measured on the live link. */
    @kotlin.concurrent.Volatile
    var fadeStepMs: Long = DEFAULT_FADE_STEP_MS
        private set

    /** Colour drags produce many states; only the newest pending one is sent. */
    private val pendingLamp = MutableStateFlow<LampState?>(null)

    private val _fade = MutableStateFlow<FadePreset?>(null)

    /** The fade being played, or null. */
    val fade: StateFlow<FadePreset?> = _fade.asStateFlow()
    private var fadeJob: Job? = null
    private var fadeBrightness = 1f

    private val _speaker = MutableStateFlow<SpeakerState>(SpeakerState.Idle)
    val speaker: StateFlow<SpeakerState> = _speaker.asStateFlow()

    /** Mirrors BluzManager.setForeground: polling pauses while the app is in the background. */
    private val foreground = MutableStateFlow(true)

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun connect(device: BulbDevice) {
        val current = _connection.value
        if (current is ConnectionState.Connecting && current.device == device) return
        if (current is ConnectionState.Connected && current.device == device) return
        session?.cancel()
        session = scope.launch { runSession(device) }
    }

    fun disconnect() {
        val link = transport
        val running = session
        session = null
        transport = null
        _connection.value = ConnectionState.Disconnected
        scope.launch {
            // Say goodbye (BluzManager.release) while the link is still open, then tear down.
            if (link != null) runCatching { withTimeoutOrNull(RELEASE_TIMEOUT_MS) { writeLock.withLock { link.write(BulbCommands.release()) } } }
            running?.cancel()
            link?.close()
        }
    }

    /** A manual lamp change; it also ends a running fade. */
    fun setLamp(state: LampState) {
        stopFade()
        showLamp(state)
    }

    private fun showLamp(state: LampState) {
        _lamp.value = state
        pendingLamp.value = state
    }

    /**
     * Plays [preset] until [stopFade] or a manual [setLamp]. Calling it again while fading swaps in
     * the new colours / timing / brightness without restarting the loop.
     */
    fun startFade(preset: FadePreset, brightness: Float) {
        fadeBrightness = brightness
        val wasRunning = _fade.value != null
        _fade.value = preset
        if (wasRunning && fadeJob?.isActive == true) return
        log(true, "fade ${preset.colors.joinToString { it.hex }} every ${preset.fadeMs} ms")
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val start = clock.now().toEpochMilliseconds()
            while (true) {
                val current = _fade.value ?: break
                // Interpolated colour for "now"; the step length comes from the measured link rate.
                showLamp(current.lampAt(clock.now().toEpochMilliseconds() - start, fadeBrightness))
                delay(fadeStepMs)
            }
        }
    }

    fun stopFade() {
        fadeJob?.cancel()
        fadeJob = null
        _fade.value = null
    }

    /**
     * Makes the bulb the phone's speaker. The bulb takes one bearer at a time and Android bonds a
     * dual-mode device over whatever link is up, so the BLE control link is dropped first; the
     * platform then pairs over Classic and waits for audio, and control reconnects (over SPP).
     */
    fun connectSpeaker() {
        val device = when (val c = _connection.value) {
            is ConnectionState.Connected -> c.device
            is ConnectionState.Failed -> c.device
            is ConnectionState.Connecting -> c.device
            ConnectionState.Disconnected -> return
        }
        if (_speaker.value == SpeakerState.Working) return
        _speaker.value = SpeakerState.Working
        scope.launch {
            stopFade()
            disconnect()
            delay(SPEAKER_HANDOVER_MS)
            _speaker.value = try {
                platform.connectSpeaker(device)
                SpeakerState.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log(false, "speaker: ${e.message}")
                SpeakerState.Failed(e.message ?: "speaker connection failed")
            }
            connect(device)
        }
    }

    fun refreshSpeaker(device: BulbDevice) {
        if (_speaker.value == SpeakerState.Working) return
        _speaker.value = if (platform.isSpeakerConnected(device)) SpeakerState.Connected else SpeakerState.Idle
    }

    fun setVolume(volume: Int) {
        _status.update { it?.copy(volume = volume) }
        send(BulbCommands.setVolume(volume), "volume $volume")
    }

    fun setEqPreset(preset: EqPreset) {
        _status.update { it?.copy(eqCode = preset.code) }
        send(BulbCommands.setEqPreset(preset), "EQ ${preset.label}")
    }

    fun setSleepTimer(seconds: Int) {
        send(BulbCommands.sleepTimer(seconds), "sleep in ${seconds}s")
        _sleepTimerEndsAt.value = clock.now().toEpochMilliseconds() + seconds * 1000L
    }

    fun cancelSleepTimer() {
        send(BulbCommands.cancelSleepTimer(), "cancel sleep timer")
        _sleepTimerEndsAt.value = null
    }

    fun rename(name: String) = send(BulbCommands.rename(name), "rename \"$name\"")

    private fun send(frame: ByteArray, label: String) {
        val link = transport ?: return
        scope.launch {
            // A failed write closes the link; the session's read loop then ends and reports it.
            runCatching { write(link, frame, label) }.onFailure { link.close() }
        }
    }

    private suspend fun runSession(device: BulbDevice) {
        _connection.value = ConnectionState.Connecting(device)
        _status.value = null
        pendingLamp.value = null
        val link = try {
            platform.connect(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.message ?: e::class.simpleName ?: "error"
            log(false, "connect failed: $reason")
            _connection.value = ConnectionState.Failed(device, reason)
            return
        }
        transport = link
        try {
            coroutineScope {
                launch { readLoop(link) }
                log(false, "link: ${link.link}")
                if (link.sendsHandshake) write(link, BulbCommands.HANDSHAKE, "handshake \"01234567\"")
                write(link, BulbCommands.queryFeatures(), "query features")
                // BluzManager waits up to 2 s for the feature reply before declaring itself ready.
                withTimeoutOrNull(FEATURE_TIMEOUT_MS) { featureReplies.receive() }
                _connection.value = ConnectionState.Connected(device)
                refreshSpeaker(device)
                val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
                write(
                    link,
                    BulbCommands.setSystemTime(now.year, now.month.ordinal + 1, now.day, now.hour, now.minute, now.second),
                    "clock sync",
                )
                write(link, BulbCommands.queryLamp(), "query lamp")
                withTimeoutOrNull(FEATURE_TIMEOUT_MS) { lampReplies.receive() }
                measureRefreshRate(link)
                launch { lampWriter(link) }
                pollLoop(link)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _connection.value = ConnectionState.Failed(device, e.message ?: "connection lost")
        } finally {
            link.close()
            if (transport === link) transport = null
        }
    }

    /**
     * How often can the colour be refreshed? Send a burst of light commands that repeat the bulb's
     * current state (nothing visible changes), then a lamp query. Its answer only arrives after the
     * bulb has handled the whole burst, so burst time / commands = real per-command time on this link.
     */
    private suspend fun measureRefreshRate(link: BulbTransport) {
        val same = BulbCommands.setLamp(_lamp.value)
        while (lampReplies.tryReceive().isSuccess) Unit
        val t0 = clock.now().toEpochMilliseconds()
        repeat(CALIBRATION_BURST) { writeLock.withLock { link.write(same) } }
        write(link, BulbCommands.queryLamp(), null)
        val answered = withTimeoutOrNull(CALIBRATION_TIMEOUT_MS) { lampReplies.receive() } != null
        val elapsed = clock.now().toEpochMilliseconds() - t0
        if (!answered) {
            log(false, "refresh test: no answer, fade steps stay at $fadeStepMs ms")
            return
        }
        val perCommand = elapsed.toDouble() / (CALIBRATION_BURST + 1)
        val maxPerSecond = 1000.0 / perCommand
        fadeStepMs = (perCommand / FADE_RATE_SHARE).toLong().coerceIn(MIN_FADE_STEP_MS, MAX_FADE_STEP_MS)
        log(
            false,
            "refresh test: ${CALIBRATION_BURST + 1} commands in $elapsed ms → max ${maxPerSecond.toInt()}/s; " +
                "fade at ${(FADE_RATE_SHARE * 100).toInt()}% → a colour every $fadeStepMs ms",
        )
    }

    private suspend fun lampWriter(link: BulbTransport) {
        pendingLamp.filterNotNull().conflate().collect { state ->
            // Fade steps are not logged one by one; startFade logs the fade once.
            write(link, BulbCommands.setLamp(state), if (fadeJob?.isActive == true) null else "lamp $state")
        }
    }

    /** BluzManager: poll, wait ≤1 s for the reply, then 400 ms; give up after 25 silent polls. */
    private suspend fun pollLoop(link: BulbTransport) {
        var misses = 0
        while (true) {
            if (!foreground.value) {
                foreground.first { it }
            }
            write(link, BulbCommands.pollStatus(), null)
            val answered = withTimeoutOrNull(POLL_TIMEOUT_MS) { statusReplies.receive() } != null
            if (answered) {
                misses = 0
            } else if (++misses >= MAX_POLL_MISSES) {
                error("bulb stopped answering")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun readLoop(link: BulbTransport) {
        val decoder = BluzFrameDecoder()
        val buffer = ByteArray(1024)
        while (true) {
            val count = link.read(buffer)
            if (count < 0) error("connection closed by the bulb")
            for (frame in decoder.feed(buffer, count)) handle(frame)
        }
    }

    private fun handle(frame: BluzFrame) {
        when (frame.key) {
            BluzKeys.RSP_STATUS -> {
                DeviceStatus.parse(frame.payload)?.let { _status.value = it }
                statusReplies.trySend(Unit)
            }
            BluzKeys.RSP_FEATURES -> {
                log(false, "features arg1=0x${frame.arg1.toUInt().toString(16)} arg2=0x${frame.arg2.toUInt().toString(16)}")
                featureReplies.trySend(Unit)
            }
            in BluzKeys.RSP_CUSTOM_RANGE -> {
                // The old app decoded every custom reply as lamp state; the lamp query answer is one.
                val state = LampState.fromArgs(frame.arg1, frame.arg2)
                log(false, "lamp $state (key 0x${frame.key.toString(16)})")
                if (pendingLamp.value == null) _lamp.value = state
                lampReplies.trySend(Unit)
            }
            else -> log(false, frame.toString())
        }
    }

    private suspend fun write(link: BulbTransport, bytes: ByteArray, label: String?) {
        // Logged before the write so a fast reply cannot appear above its request.
        writeLock.withLock {
            if (label != null) log(true, "$label  ${bytes.toHex()}")
            link.write(bytes)
        }
    }


    private fun log(outgoing: Boolean, text: String) {
        _log.update { (it + LogEntry(outgoing, text)).takeLast(MAX_LOG) }
    }

    companion object {
        const val FEATURE_TIMEOUT_MS: Long = 2_000
        const val POLL_TIMEOUT_MS: Long = 1_000
        const val POLL_INTERVAL_MS: Long = 400
        const val MAX_POLL_MISSES: Int = 25

        /** Pause after dropping BLE so the bulb is back in a state that accepts Classic. */
        const val SPEAKER_HANDOVER_MS: Long = 1_500
        private const val RELEASE_TIMEOUT_MS = 1_000L
        private const val CALIBRATION_BURST = 20
        private const val CALIBRATION_TIMEOUT_MS = 8_000L

        /** Share of the measured maximum colour rate used for fades (headroom for polls / jitter). */
        const val FADE_RATE_SHARE: Double = 0.7
        const val DEFAULT_FADE_STEP_MS: Long = 100
        private const val MIN_FADE_STEP_MS = 20L
        private const val MAX_FADE_STEP_MS = 500L
        private const val MAX_LOG = 200
    }
}
