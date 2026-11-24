package dev.lolokeksu.scrimroom.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lolokeksu.scrimroom.data.OnlineMatch
import dev.lolokeksu.scrimroom.data.RecentPlayer
import dev.lolokeksu.scrimroom.data.SocialPlayer
import dev.lolokeksu.scrimroom.data.OnlinePlayer
import dev.lolokeksu.scrimroom.model.AssignedPlayer
import dev.lolokeksu.scrimroom.model.DemoResult
import dev.lolokeksu.scrimroom.model.MatchDraft
import dev.lolokeksu.scrimroom.model.PlayerProfile
import dev.lolokeksu.scrimroom.model.Rank
import dev.lolokeksu.scrimroom.model.Role
import dev.lolokeksu.scrimroom.model.Side
import dev.lolokeksu.scrimroom.model.Verification
import dev.lolokeksu.scrimroom.model.exampleQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val complaintLabels = linkedMapOf("AFK" to "AFK", "ABUSE" to "Оскорбления", "CHEATING" to "Читы", "GRIEFING" to "Саботаж", "OTHER" to "Другое")

@Composable
fun CommunityScreen(state: ScrimUiState, vm: ScrimViewModel, onSessions: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var inviteTo by remember { mutableStateOf<SocialPlayer?>(null) }
    var reportTo by remember { mutableStateOf<RecentPlayer?>(null) }
    var category by remember { mutableStateOf("AFK") }
    var note by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Друзья") }
    LaunchedEffect(state.onlineStatus, state.serverUrl) {
        if (state.onlineStatus == "APPROVED") vm.loadCommunity()
    }
    Page {
        Eyebrow("Клуб / участники")
        Headline("Сообщество", "Связи между игроками одного региона. Жалобы рассматривает модератор.")
        if (state.onlineStatus != "APPROVED") {
            PanelCard { Text("Сообщество доступно после допуска игрока.", color = Muted) }
        } else {
            SecondaryAction("Обновить сообщество", enabled = !state.onlineBusy) { vm.loadCommunity() }
            ChoiceRow("Раздел", listOf("Друзья", "Приглашения", "Жалобы"), filter, { it }) { filter = it }
            val data = state.community
            if (data == null) Text("Данные ещё не загружены", color = Muted)
            if (filter == "Друзья") {
                PanelCard {
                    OutlinedTextField(query, { query = it.take(64) }, label = { Text("Точный Riot ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    PrimaryAction("Найти игрока", enabled = !state.onlineBusy && query.length >= 5 && query.contains("#")) { vm.findCommunityPlayer(query) }
                    state.foundPlayer?.let { player ->
                        Text(player.riotId, fontWeight = FontWeight.Bold)
                        Text("Роли: ${Role.entries.firstOrNull { it.name == player.primary }?.title ?: player.primary} / ${Role.entries.firstOrNull { it.name == player.secondary }?.title ?: player.secondary}", color = Muted, fontSize = 12.sp)
                        if (player.relationship == "NONE") SecondaryAction("Отправить запрос", enabled = !state.onlineBusy) { vm.requestFriend(player.id) }
                        else Text("Связь уже существует. Смотри список друзей.", color = Muted, fontSize = 12.sp)
                    }
                }
                data?.friends?.forEach { player ->
                    PanelCard {
                        Text(player.riotId, fontWeight = FontWeight.Bold)
                        Text(when(player.relationship) { "FRIEND" -> "Друг"; "INCOMING" -> "Входящий запрос"; else -> "Запрос отправлен" }, color = Accent, fontSize = 12.sp)
                        if (player.relationship == "INCOMING") PrimaryAction("Принять", enabled = !state.onlineBusy) { vm.changeFriend(player.id,true) }
                        if (player.relationship == "FRIEND") PrimaryAction("Пригласить на сессию", enabled = !state.onlineBusy) { inviteTo = player }
                        SecondaryAction(if (player.relationship == "FRIEND") "Удалить из друзей" else "Отклонить / отменить", enabled = !state.onlineBusy) { vm.changeFriend(player.id,false) }
                    }
                }
                if (data?.friends?.isEmpty() == true) Text("Пока нет друзей и запросов", color = Muted)
            }
            if (filter == "Приглашения") {
                data?.invites?.forEach { invitation ->
                    PanelCard {
                        Text(invitation.title, fontWeight = FontWeight.Bold)
                        Text("Пригласил: ${invitation.sender}", color = Muted)
                        Text(SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(invitation.startsAt*1000)), fontSize = 12.sp)
                        PrimaryAction("Открыть расписание") { onSessions() }
                        SecondaryAction("Скрыть приглашение", enabled = !state.onlineBusy) { vm.dismissInvite(invitation.id) }
                    }
                }
                if (data?.invites?.isEmpty() == true) Text("Нет входящих приглашений", color = Muted)
            }
            if (filter == "Жалобы") {
                PanelCard {
                    Text("Подтверждённых жалоб на тебя: ${data?.confirmedComplaints ?: "—"}", fontWeight = FontWeight.Bold)
                    Text("Жалоба не изменяет оценку мастерства автоматически. Отправляй только обоснованные обращения.", color = Muted, fontSize = 12.sp)
                    data?.reports?.forEach { r ->
                        Text((complaintLabels[r.category] ?: r.category) + " · " + when(r.status) { "UPHELD" -> "Подтверждена"; "DISMISSED" -> "Отклонена"; else -> "На проверке" }, fontSize = 13.sp)
                    }
                }
                Eyebrow("Участники недавних матчей")
                if (data?.recent?.isEmpty() == true) Text("Нет недавних совместных матчей", color = Muted)
                data?.recent?.groupBy { it.matchId }?.values?.forEach { group ->
                    PanelCard {
                        Text(SimpleDateFormat("dd.MM HH:mm",Locale("ru")).format(Date(group.first().createdAt*1000)), color = Accent, fontSize = 12.sp)
                        group.forEach { player ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(player.riotId, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = { reportTo = player; category = "AFK"; note = "" }, enabled = !state.onlineBusy) { Text("Жалоба") }
                            }
                        }
                    }
                }
            }
        }
    }
    inviteTo?.let { player ->
        val sessions = state.sessions.orEmpty().filter { it.enrolled && it.status in listOf("OPEN","UPCOMING") }
        AlertDialog(onDismissRequest = { inviteTo = null }, title = { Text("Пригласить ${player.riotId}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sessions.isEmpty()) Text("Сначала запишись на игровую сессию.")
                sessions.take(10).forEach { event -> SecondaryAction(event.title, enabled = !state.onlineBusy) { inviteTo = null; vm.inviteFriend(player.id,event.id) } }
            } }, confirmButton = { TextButton(onClick = { inviteTo = null }) { Text("Закрыть") } })
    }
    reportTo?.let { player ->
        AlertDialog(onDismissRequest = { reportTo = null }, title = { Text("Жалоба на ${player.riotId}") },
            text = { Column {
                ChoiceRow("Причина", complaintLabels.keys.toList(), category, { complaintLabels[it] ?: it }) { category = it }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Описание (необязательно)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            } }, confirmButton = { TextButton(onClick = { reportTo = null; vm.sendComplaint(player.matchId,player.accountId,category,note) }, enabled = !state.onlineBusy) { Text("Отправить") } },
            dismissButton = { TextButton(onClick = { reportTo = null }) { Text("Отмена") } })
    }
}
