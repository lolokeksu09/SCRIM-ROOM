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
