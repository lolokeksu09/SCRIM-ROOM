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

/** Community and competitive mutations share the session coordinator to preserve account isolation. */
internal class CommunityActions(
    private val store: LocalStore,
    private val state: MutableStateFlow<ScrimUiState>,
    private val runAction: (suspend () -> Unit) -> Unit
) {
    private fun communityAction(action: (RemoteClient, String) -> Unit = { _, _ -> }) = runAction {
        val token = store.readServerToken()
        if (token.isBlank()) return@runAction
        val api = RemoteClient(store.readServerUrl())
        action(api, token)
        val data = api.community(token)
        val sessions = api.sessions(token)
        state.update { it.copy(community = data, sessions = sessions, foundPlayer = null) }
    }
    fun loadCommunity() = communityAction()
    fun findCommunityPlayer(riotId: String) = runAction {
        state.update { it.copy(foundPlayer = null) }
        val player = RemoteClient(store.readServerUrl()).findPlayer(store.readServerToken(), riotId.trim())
        state.update { it.copy(foundPlayer = player) }
    }
    fun requestFriend(id: String) = communityAction { api, token -> api.requestFriend(token,id) }
    fun changeFriend(id: String, accept: Boolean) = communityAction { api, token -> api.friendAction(token,id,accept) }
    fun inviteFriend(id: String, session: String) = communityAction { api, token ->
        api.inviteFriend(token,id,session)
        state.update { it.copy(message = "Приглашение отправлено") }
    }
    fun dismissInvite(id: String) = communityAction { api, token -> api.dismissInvite(token,id) }
    fun sendComplaint(match: String, id: String, category: String, note: String) = communityAction { api, token ->
        api.complain(token,match,id,category,note)
        state.update { it.copy(message = "Жалоба отправлена модератору") }
    }

    private fun clubAction(action:(RemoteClient,String)->Unit={_,_->}) = runAction {
        val token=store.readServerToken()
        if(token.isBlank()) return@runAction
        val api=RemoteClient(store.readServerUrl())
        action(api,token)
        val club=api.competitive(token)
        state.update { it.copy(club=club) }
    }
    fun loadClub()=clubAction()
    fun createClubTeam(name:String)=clubAction { api,t -> api.createClubTeam(t,name) }
    fun inviteClubMember(riotId:String)=clubAction { api,t -> api.inviteClubMember(t,riotId); state.update { it.copy(message="Приглашение отправлено") } }
    fun clubInviteAction(id:String,accept:Boolean)=clubAction { api,t -> api.clubInviteAction(t,id,accept) }
    fun leaveClubTeam()=clubAction { api,t -> api.leaveClubTeam(t) }
    fun clubMemberAction(id:String,transfer:Boolean)=clubAction { api,t -> api.clubMemberAction(t,id,transfer) }
    fun createClubChallenge(opponent:String,season:String,starts:Long)=clubAction { api,t -> api.createClubChallenge(t,opponent,season,starts) }
    fun clubChallengeAction(id:String,accept:Boolean)=clubAction { api,t -> api.clubChallengeAction(t,id,accept) }

}
