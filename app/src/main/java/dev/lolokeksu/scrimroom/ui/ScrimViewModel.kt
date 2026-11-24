package dev.lolokeksu.scrimroom.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lolokeksu.scrimroom.data.LocalStore
import dev.lolokeksu.scrimroom.data.OnlineQueueState
import dev.lolokeksu.scrimroom.data.OnlineResult
import dev.lolokeksu.scrimroom.data.PlaySession
import dev.lolokeksu.scrimroom.data.OcrSuggestions
import dev.lolokeksu.scrimroom.data.RemoteClient
import dev.lolokeksu.scrimroom.data.toOnlineQueueState
import dev.lolokeksu.scrimroom.match.MatchEngine
import dev.lolokeksu.scrimroom.model.DemoResult
import dev.lolokeksu.scrimroom.model.MatchDraft
import dev.lolokeksu.scrimroom.model.PlayerProfile
import dev.lolokeksu.scrimroom.model.Side
import dev.lolokeksu.scrimroom.model.Verification
import dev.lolokeksu.scrimroom.model.exampleQueue
import dev.lolokeksu.scrimroom.model.validateProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ScrimUiState(
    val profile: PlayerProfile = PlayerProfile(),
    val demoMatch: MatchDraft? = null,
    val demoScrollRequest: Long = 0,
    val demoHistory: List<DemoResult> = emptyList(),
    val serverUrl: String = "",
    val hasOnlineAccount: Boolean = false,
    val onlineStatus: String? = null,
    val onlineEvidenceCount: Int = 0,
    val onlineQueue: OnlineQueueState? = null,
    val onlineResults: List<OnlineResult>? = null,
    val sessions: List<PlaySession>? = null,
    val ocrSuggestions: OcrSuggestions? = null,
    val onlineBusy: Boolean = false,
    val message: String? = null
)

class ScrimViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val onlineMutex = Mutex()
    private val _state = MutableStateFlow(
        ScrimUiState(
            profile = store.readProfile(),
            demoHistory = store.readResults(),
            serverUrl = store.readServerUrl(),
            hasOnlineAccount = store.readServerToken().isNotEmpty()
        )
    )
    val state = _state.asStateFlow()

    init {
        if (store.readServerUrl().isNotBlank() && store.readServerToken().isNotBlank()) syncOnline()
    }

    fun saveServerUrl(raw: String) {
        if (state.value.onlineBusy) {
            _state.update { it.copy(message = "Заверши текущую сетевую операцию, прежде чем менять сервер.") }
            return
        }
        val normalized = raw.trim().trimEnd('/')
        val error = runCatching { RemoteClient(normalized) }.exceptionOrNull()
        if (error != null) {
            _state.update { it.copy(message = error.message ?: "Неверный адрес сервера") }
            return
        }
        val changed = store.readServerUrl() != normalized
        store.writeServerUrl(normalized)
        _state.update { it.copy(serverUrl = normalized,
            hasOnlineAccount = if (changed) false else it.hasOnlineAccount,
            onlineStatus = if (changed) null else it.onlineStatus,
            onlineQueue = if (changed) null else it.onlineQueue,
            onlineResults = if (changed) null else it.onlineResults,
            sessions = if (changed) null else it.sessions,
            ocrSuggestions = if (changed) null else it.ocrSuggestions,
            onlineEvidenceCount = if (changed) 0 else it.onlineEvidenceCount,
            message = if (changed) "HTTPS-сервер сохранён. Привязка к прежнему серверу сброшена." else "Адрес сервера сохранён."
        ) }
        if (!changed && store.readServerToken().isNotEmpty()) syncOnline()
    }

    /** Local save is not proof of review or a network submission. */
    fun register(profile: PlayerProfile) {
        val cleaned = cleaned(profile)
        val error = validateProfile(cleaned)
        if (error != null) {
            _state.update { it.copy(message = error) }
            return
        }
        val saved = cleaned.copy(verification = Verification.PENDING_REVIEW)
        store.writeProfile(saved)
        _state.update { it.copy(profile = saved, message = "Черновик сохранён на устройстве. Для проверки отправь онлайн-заявку.") }
    }

    fun submitOnline(profile: PlayerProfile) {
        val cleaned = cleaned(profile)
        val error = validateProfile(cleaned)
        if (error != null) {
            _state.update { it.copy(message = error) }
            return
        }
        val saved = cleaned.copy(verification = Verification.PENDING_REVIEW)
        store.writeProfile(saved)
        _state.update { it.copy(profile = saved, ocrSuggestions = null) }
        onlineAction {
            val api = RemoteClient(store.readServerUrl())
            val fingerprint = fingerprint(saved)
            var token = store.readServerToken()
            if (token.isBlank()) {
                val response = api.register(saved)
                token = response.getString("token")
                store.writeServerToken(token)
                store.writeSubmittedFingerprint(fingerprint)
                store.writeUploadedUris(emptySet())
                applyAccount(response.getJSONObject("account"))
            } else if (store.readSubmittedFingerprint() != fingerprint) {
                applyAccount(api.updateProfile(token, saved))
                _state.update { it.copy(onlineQueue = null) }
                store.writeSubmittedFingerprint(fingerprint)
                store.writeUploadedUris(emptySet())
            }
            // Upload occurs only after an explicit consented button press.
            val sent = store.readUploadedUris().toMutableSet()
            for (uri in saved.screenshots.distinct().take(4)) {
                if (uri in sent) continue
                api.uploadEvidence(token, getApplication<Application>().contentResolver, Uri.parse(uri))
                sent += uri
                store.writeUploadedUris(sent)
            }
            val account = api.account(token)
            applyAccount(account)
            val count = account.optInt("evidence_count")
            _state.update { it.copy(message = if (count >= 2)
                "Заявка и $count скриншота(ов) отправлены. Решение принимает модератор."
                else "Заявка создана, но загружено только $count изображений. Нужны минимум два.") }
        }
    }

    private fun cleaned(profile: PlayerProfile) = profile.copy(
        riotId = profile.riotId.trim(), region = profile.region.trim(),
        screenshots = profile.screenshots.distinct(), verification = Verification.DRAFT
    )

    private fun fingerprint(profile: PlayerProfile) = listOf(
        profile.riotId, profile.region.uppercase(), profile.currentRank.name,
        profile.previousRank.name, profile.primary.name, profile.secondary.name
    ).joinToString("|")

    private fun applyAccount(account: JSONObject) {
        _state.update { it.copy(hasOnlineAccount = true,
            onlineStatus = account.optString("status"),
            onlineEvidenceCount = account.optInt("evidence_count")) }
    }

    private fun onlineAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!onlineMutex.tryLock()) return@launch
            _state.update { it.copy(onlineBusy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "Ошибка подключения к серверу") }
            } finally {
                _state.update { it.copy(onlineBusy = false) }
                onlineMutex.unlock()
            }
        }
    }

    fun syncOnline() = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        val api = RemoteClient(store.readServerUrl())
        applyAccount(api.account(token))
        _state.update { it.copy(onlineQueue = api.state(token).toOnlineQueueState()) }
    }

    fun refreshQueue() = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        val queue = RemoteClient(store.readServerUrl()).state(token).toOnlineQueueState()
        _state.update { it.copy(onlineQueue = queue, onlineStatus = queue.accountStatus.takeIf(String::isNotBlank) ?: it.onlineStatus) }
    }

    fun joinOnlineQueue(sessionId: String? = null) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        require(token.isNotBlank()) { "Сначала отправь заявку" }
        api.join(token, sessionId)
        _state.update { it.copy(onlineQueue = api.state(token).toOnlineQueueState()) }
    }

    fun loadSessions() = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        val sessions = RemoteClient(store.readServerUrl()).sessions(token)
        _state.update { it.copy(sessions = sessions) }
    }

    fun changeSession(id: String, enroll: Boolean) = onlineAction {
        val token = store.readServerToken()
        val api = RemoteClient(store.readServerUrl())
        if (enroll) api.signup(token, id) else api.withdraw(token, id)
        val sessions = api.sessions(token)
        val queue = api.state(token).toOnlineQueueState()
        _state.update { it.copy(sessions = sessions, onlineQueue = queue,
            message = if (enroll) "Место забронировано. В назначенное время войди в очередь сессии." else "Запись отменена") }
    }

    fun leaveOnlineQueue() = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.leave(token)
        _state.update { it.copy(onlineQueue = api.state(token).toOnlineQueueState()) }
    }

    fun readyOnlineMatch(matchId: String) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.ready(token, matchId)
        _state.update { it.copy(onlineQueue = api.state(token).toOnlineQueueState()) }
    }

    fun declineOnlineMatch(matchId: String) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.decline(token, matchId)
        _state.update { it.copy(onlineQueue = api.state(token).toOnlineQueueState()) }
    }

    fun reportOnlineResult(matchId: String, winner: Side) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        val response = api.reportResult(token, matchId, winner.name)
        val queue = api.state(token).toOnlineQueueState()
        _state.update { it.copy(onlineQueue = queue, message = when(response.optString("status")) {
            "CLOSED" -> "Результат подтверждён"
            "DISPUTED" -> "Результаты не совпали. Требуется решение модератора"
            else -> "Результат отправлен. Ожидаем второго капитана"
        }) }
    }

    fun loadOnlineResults() = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        val results = RemoteClient(store.readServerUrl()).results(token)
        _state.update { it.copy(onlineResults = results) }
    }

    fun recognizeEvidence(slot: Int) = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        _state.update { it.copy(ocrSuggestions = null) }
        val suggestions = RemoteClient(store.readServerUrl()).recognizeEvidence(token, slot)
        _state.update { it.copy(ocrSuggestions = suggestions, message = "Распознавание завершено. Проверь значения по оригиналу.") }
    }

    fun persistDocumentUris(uris: List<Uri>): List<String> {
        val resolver = getApplication<Application>().contentResolver
        val accepted = mutableListOf<String>()
        uris.distinct().forEach { uri ->
            try {
                if (resolver.getType(uri) !in setOf("image/png", "image/jpeg", "image/webp")) return@forEach
                resolver.openInputStream(uri)?.use { if (it.read() == -1) return@forEach }
                    ?: return@forEach
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                accepted += uri.toString()
            } catch (_: SecurityException) {
                // Inaccessible images must never count as evidence.
            } catch (_: Exception) {
                // Corrupt or deleted document; ignore it.
            }
        }
        if (accepted.size != uris.distinct().size) {
            _state.update { it.copy(message = "Некоторые файлы не удалось прочитать. Нужны PNG/JPEG/WEBP до 4 МБ.") }
        }
        return accepted
    }

    fun buildDemoMatch() {
        if (state.value.demoMatch != null) return
        runCatching { MatchEngine.build(exampleQueue()) }
            .onSuccess { match -> _state.update { it.copy(demoMatch = match, message = null) } }
            .onFailure { error -> _state.update { it.copy(message = error.message ?: "Ошибка при формировании матча") } }
    }

    fun requestDemoTeams() {
        _state.update { if (it.demoMatch != null) it.copy(demoScrollRequest = it.demoScrollRequest + 1) else it }
    }

    fun finishDemo(winner: Side) {
        val match = state.value.demoMatch ?: return
        val result = DemoResult(
            id = System.currentTimeMillis(), winner = winner,
            blueAverage = match.blueAverage, redAverage = match.redAverage
        )
        val next = (listOf(result) + state.value.demoHistory).take(50)
        store.writeResults(next)
        _state.update { it.copy(demoMatch = null, demoHistory = next, message = "Демо-матч сохранён локально") }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
