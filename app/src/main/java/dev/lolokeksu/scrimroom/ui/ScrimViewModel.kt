package dev.lolokeksu.scrimroom.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lolokeksu.scrimroom.data.LocalStore
import dev.lolokeksu.scrimroom.data.OnlineQueueState
import dev.lolokeksu.scrimroom.data.OnlineResult
import dev.lolokeksu.scrimroom.data.PersonalStats
import dev.lolokeksu.scrimroom.data.PlaySession
import dev.lolokeksu.scrimroom.data.CommunityData
import dev.lolokeksu.scrimroom.data.ClubData
import dev.lolokeksu.scrimroom.data.InboxItem
import dev.lolokeksu.scrimroom.QueueWatchService
import dev.lolokeksu.scrimroom.data.RemoteException
import dev.lolokeksu.scrimroom.model.Rank
import dev.lolokeksu.scrimroom.model.Role
import dev.lolokeksu.scrimroom.data.SocialPlayer
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
    val personalStats: PersonalStats? = null,
    val sessions: List<PlaySession>? = null,
    val community: CommunityData? = null,
    val club: ClubData? = null,
    val inbox: List<InboxItem>? = null,
    val discoverable: Boolean? = null,
    val recoveryCode: String? = null,
    val foundPlayer: SocialPlayer? = null,
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
        if (changed) QueueWatchService.stop(getApplication<Application>())
        store.writeServerUrl(normalized)
        _state.update { it.copy(serverUrl = normalized,
            hasOnlineAccount = if (changed) false else it.hasOnlineAccount,
            onlineStatus = if (changed) null else it.onlineStatus,
            onlineQueue = if (changed) null else it.onlineQueue,
            onlineResults = if (changed) null else it.onlineResults,
            personalStats = if (changed) null else it.personalStats,
            sessions = if (changed) null else it.sessions,
            community = if (changed) null else it.community,
            club = if (changed) null else it.club,
            inbox = if (changed) null else it.inbox,
            discoverable = if (changed) null else it.discoverable,
            recoveryCode = null,
            foundPlayer = if (changed) null else it.foundPlayer,
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

    private fun onlineAction(background: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!onlineMutex.tryLock()) {
                if (!background) _state.update { it.copy(message = "Выполняется другой запрос. Повтори действие после его завершения.") }
                return@launch
            }
            _state.update { it.copy(onlineBusy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is RemoteException && error.statusCode == 401 && store.readServerToken().isNotBlank()) {
                    store.clearCredentials()
                    QueueWatchService.stop(getApplication<Application>())
                    _state.update { it.copy(hasOnlineAccount=false, onlineStatus=null, onlineQueue=null, onlineEvidenceCount=0, onlineResults=null, personalStats=null, community=null, club=null, sessions=null, foundPlayer=null, ocrSuggestions=null,inbox=null,discoverable=null,recoveryCode=null) }
                }
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

    fun refreshQueue(background: Boolean = false) = onlineAction(background) {
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
        val api = RemoteClient(store.readServerUrl())
        val results = api.results(token)
        val stats = api.statistics(token)
        _state.update { it.copy(onlineResults = results,personalStats = stats) }
    }

    fun setLobby(matchId: String, room: String) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.setLobby(token,matchId,room.trim())
        _state.update { it.copy(onlineQueue=api.state(token).toOnlineQueueState(),message="Лобби сохранено") }
    }
    fun arriveLobby(matchId: String) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.arriveLobby(token,matchId)
        _state.update { it.copy(onlineQueue=api.state(token).toOnlineQueueState()) }
    }
    fun uploadResultEvidence(matchId: String, uri: Uri) = onlineAction {
        val api = RemoteClient(store.readServerUrl())
        val token = store.readServerToken()
        api.uploadResultEvidence(token,matchId,getApplication<Application>().contentResolver,uri)
        _state.update { it.copy(onlineQueue=api.state(token).toOnlineQueueState(),message="Скриншот результата сохранён") }
    }

    fun recognizeEvidence(slot: Int) = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        _state.update { it.copy(ocrSuggestions = null) }
        val suggestions = RemoteClient(store.readServerUrl()).recognizeEvidence(token, slot)
        _state.update { it.copy(ocrSuggestions = suggestions, message = "Распознавание завершено. Проверь значения по оригиналу.") }
    }

    private fun communityAction(action: (RemoteClient, String) -> Unit = { _, _ -> }) = onlineAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@onlineAction
        val api = RemoteClient(store.readServerUrl())
        action(api, token)
        val data = api.community(token)
        val sessions = api.sessions(token)
        _state.update { it.copy(community = data, sessions = sessions, foundPlayer = null) }
    }
    fun loadCommunity() = communityAction()
    fun findCommunityPlayer(riotId: String) = onlineAction {
        _state.update { it.copy(foundPlayer = null) }
        val player = RemoteClient(store.readServerUrl()).findPlayer(store.readServerToken(), riotId.trim())
        _state.update { it.copy(foundPlayer = player) }
    }
    fun requestFriend(id: String) = communityAction { api, token -> api.requestFriend(token,id) }
    fun changeFriend(id: String, accept: Boolean) = communityAction { api, token -> api.friendAction(token,id,accept) }
    fun inviteFriend(id: String, session: String) = communityAction { api, token ->
        api.inviteFriend(token,id,session)
        _state.update { it.copy(message = "Приглашение отправлено") }
    }
    fun dismissInvite(id: String) = communityAction { api, token -> api.dismissInvite(token,id) }
    fun sendComplaint(match: String, id: String, category: String, note: String) = communityAction { api, token ->
        api.complain(token,match,id,category,note)
        _state.update { it.copy(message = "Жалоба отправлена модератору") }
    }

    private fun clubAction(action:(RemoteClient,String)->Unit={_,_->}) = onlineAction {
        val token=store.readServerToken()
        if(token.isBlank()) return@onlineAction
        val api=RemoteClient(store.readServerUrl())
        action(api,token)
        val club=api.competitive(token)
        _state.update { it.copy(club=club) }
    }
    fun loadClub()=clubAction()
    fun createClubTeam(name:String)=clubAction { api,t -> api.createClubTeam(t,name) }
    fun inviteClubMember(riotId:String)=clubAction { api,t -> api.inviteClubMember(t,riotId); _state.update { it.copy(message="Приглашение отправлено") } }
    fun clubInviteAction(id:String,accept:Boolean)=clubAction { api,t -> api.clubInviteAction(t,id,accept) }
    fun leaveClubTeam()=clubAction { api,t -> api.leaveClubTeam(t) }
    fun clubMemberAction(id:String,transfer:Boolean)=clubAction { api,t -> api.clubMemberAction(t,id,transfer) }
    fun createClubChallenge(opponent:String,season:String,starts:Long)=clubAction { api,t -> api.createClubChallenge(t,opponent,season,starts) }
    fun clubChallengeAction(id:String,accept:Boolean)=clubAction { api,t -> api.clubChallengeAction(t,id,accept) }

    fun loadAccountSettings()=onlineAction {
        val token=store.readServerToken()
        if(token.isBlank()) return@onlineAction
        val api=RemoteClient(store.readServerUrl())
        val privacy=api.discoverable(token)
        val inbox=api.inbox(token)
        _state.update { it.copy(discoverable=privacy,inbox=inbox) }
    }
    fun issueRecovery()=onlineAction {
        val code=RemoteClient(store.readServerUrl()).issueRecovery(store.readServerToken())
        _state.update { it.copy(recoveryCode=code) }
    }
    fun hideRecovery(){_state.update {it.copy(recoveryCode=null)}}
    fun recoverAccount(code:String)=onlineAction {
        require(store.readServerToken().isBlank()){"Сначала отключи текущий аккаунт"}
        val api=RemoteClient(store.readServerUrl())
        val response=api.recover(code.trim())
        val a=response.getJSONObject("account")
        val profile=PlayerProfile(riotId=a.getString("riot_id"),region=a.getString("region"),
            currentRank=Rank.valueOf(a.getString("current_rank")),previousRank=Rank.valueOf(a.getString("previous_rank")),
            primary=Role.valueOf(a.getString("primary")),secondary=Role.valueOf(a.getString("secondary")),verification=Verification.PENDING_REVIEW)
        store.writeServerToken(response.getString("token"))
        store.writeProfile(profile)
        store.writeSubmittedFingerprint(fingerprint(profile))
        store.writeUploadedUris(emptySet())
        _state.update { it.copy(profile=profile,hasOnlineAccount=true,onlineStatus=a.getString("status"),onlineEvidenceCount=a.getInt("evidence_count"),message="Доступ восстановлен. Создай новый код восстановления.") }
    }
    fun disconnectAccount(){
        if(state.value.onlineBusy)return
        QueueWatchService.stop(getApplication<Application>())
        store.clearCredentials()
        _state.update {it.copy(hasOnlineAccount=false,onlineStatus=null,onlineEvidenceCount=0,onlineQueue=null,onlineResults=null,personalStats=null,sessions=null,community=null,club=null,foundPlayer=null,ocrSuggestions=null,inbox=null,discoverable=null,recoveryCode=null,message="Аккаунт отключён на этом устройстве")}
    }
    fun setDiscoverable(value:Boolean)=onlineAction {
        val changed=RemoteClient(store.readServerUrl()).setDiscoverable(store.readServerToken(),value)
        _state.update {it.copy(discoverable=changed)}
    }
    fun readInbox()=onlineAction {
        val token=store.readServerToken()
        val api=RemoteClient(store.readServerUrl())
        api.readInbox(token,state.value.inbox.orEmpty().filter{!it.read}.map{it.id})
        val items=api.inbox(token)
        _state.update {it.copy(inbox=items)}
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
