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

@Composable
internal fun OnlineMatchCard(match: OnlineMatch, vm: ScrimViewModel, busy: Boolean) {
    var remaining by remember(match.id,match.serverNow,match.receivedElapsed) { mutableStateOf((match.deadline-match.serverNow).coerceAtLeast(0)) }
    LaunchedEffect(match.id,match.deadline,match.serverNow,match.receivedElapsed,match.status) {
        if (match.status=="READY_CHECK" && match.serverNow>0) {
            while (isActive) {
                val elapsed=(android.os.SystemClock.elapsedRealtime()-match.receivedElapsed)/1000
                remaining=(match.deadline-match.serverNow-elapsed).coerceAtLeast(0)
                if (remaining==0L) break
                delay(250)
            }
        }
    }
    var room by remember(match.id,match.lobbyRoom) { mutableStateOf(match.lobbyRoom.orEmpty()) }
    var proofUri by remember(match.id) { mutableStateOf<android.net.Uri?>(null) }
    val resultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> proofUri=uri }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when(match.status) { "READY_CHECK" -> "Матч найден · подтверждение"; "DISPUTED" -> "Результат оспаривается"; else -> "Матч подтверждён" },
            fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Готово игроков: ${match.readyCount}/10", color = Accent, fontSize = 13.sp)
        match.quality?.let { quality ->
            Eyebrow("Баланс составов")
            Text("Разница средней оценки: ${quality.averageGap} · Вторых ролей: ${quality.secondaryCount}",color=Muted,fontSize=12.sp)
            Text("Повторных пар союзников: ${quality.repeatedPairs}. Оценка не гарантирует равный исход.",color=Muted,fontSize=12.sp)
        }
        if (match.status == "READY_CHECK") {
            Text("Подтверди готовность в течение трёх минут после создания матча. Если не все ответят, матч отменится.", color = Muted, fontSize = 12.sp)
            if (match.serverNow>0) Text(String.format(Locale.ROOT,"%02d:%02d",remaining/60,remaining%60), color = if (remaining<=20) Red else Accent, fontWeight = FontWeight.Black, fontSize = 32.sp)
            PrimaryAction(if (match.ownReady) "Готовность подтверждена" else "Я готов", enabled = !busy && !match.ownReady && (match.serverNow==0L || remaining>0)) { vm.readyOnlineMatch(match.id) }
            SecondaryAction("Отказаться", enabled = !busy && (match.serverNow==0L || remaining>0)) { vm.declineOnlineMatch(match.id) }
        }
        if (match.status in listOf("ACTIVE","DISPUTED") && match.phaseExpiresAt>0) {
            Text("Срок завершения: "+SimpleDateFormat("dd.MM HH:mm",Locale("ru")).format(Date(match.phaseExpiresAt*1000)),color=Muted,fontSize=12.sp)
            Text("Если результат не подтверждён к этому времени, матч аннулируется без изменения оценки.",color=Muted,fontSize=12.sp)
        }
        if (match.status == "ACTIVE") {
            Eyebrow("Игровое лобби")
            Text("Капитан синих создаёт комнату в Wild Rift вручную. Не вводи пароль Riot или ссылку.", color = Muted, fontSize = 12.sp)
            if (match.lobbyHost && match.lobbyArrivals == 0) {
                OutlinedTextField(room,{room=it.take(64)},label={Text("Название комнаты")},singleLine=true,modifier=Modifier.fillMaxWidth())
                SecondaryAction("Сохранить лобби",enabled=!busy && room.trim().length>=3) { vm.setLobby(match.id,room) }
            }
            Text(match.lobbyRoom ?: "Ожидаем название комнаты",fontWeight=FontWeight.Bold)
            Text("Отметились в лобби: ${match.lobbyArrivals}/10",color=Accent,fontSize=13.sp)
            if (match.lobbyRoom != null) PrimaryAction(if(match.ownArrived) "Вход отмечен" else "Я в лобби",enabled=!busy && !match.ownArrived) { vm.arriveLobby(match.id) }
        }
        OnlineTeamPanel("Синяя команда", match.blue, Blue)
        OnlineTeamPanel("Красная команда", match.red, Red)
        if (match.status in listOf("ACTIVE","DISPUTED") && match.isCaptain) {
            Eyebrow("Доказательство результата")
            Text("Скриншотов от капитанов: ${match.evidenceCount}/2. Без двух разных скриншотов совпадающие результаты отправляются модератору. Снимок сам по себе не доказывает результат.",color=Muted,fontSize=12.sp)
            if (match.ownEvidence) StatusPill("Скриншот сохранён",active=true)
            else {
                SecondaryAction("Выбрать скриншот результата",enabled=!busy) { resultPicker.launch(arrayOf("image/png","image/jpeg","image/webp")) }
                proofUri?.let { uri ->
                    AlertDialog(onDismissRequest={proofUri=null},title={Text("Отправить скриншот?")},
                        text={Text("Изображение будет доступно модератору. После отправки его нельзя заменить. Проверь, что на снимке нет личных данных.")},
                        confirmButton={TextButton(onClick={proofUri=null;vm.uploadResultEvidence(match.id,uri)},enabled=!busy){Text("Отправить")}},
                        dismissButton={TextButton(onClick={proofUri=null}){Text("Отмена")}})
                }
            }
        }
        if (match.status == "ACTIVE") {
            Text("После игры капитаны барон-линии сообщают победителя.", color = Muted, fontSize = 12.sp)
            Text("Подтверждений результата: ${match.reportCount}/2", color = Accent, fontSize = 13.sp)
            if (match.isCaptain && match.ownReport == null) {
                var selected by rememberSaveable(match.id) { mutableStateOf<Side?>(null) }
                PrimaryAction("Победа синей команды", enabled = !busy) { selected = Side.BLUE }
                SecondaryAction("Победа красной команды", enabled = !busy) { selected = Side.RED }
                selected?.let { winner ->
                    AlertDialog(onDismissRequest = { selected = null },
                        title = { Text("Подтвердить результат?") },
                        text = { Text("Победили ${if (winner == Side.BLUE) "синие" else "красные"}. После отправки решение нельзя изменить самостоятельно.") },
                        confirmButton = { TextButton(onClick = { selected = null; vm.reportOnlineResult(match.id, winner) }, enabled = !busy) { Text("Подтвердить") } },
                        dismissButton = { TextButton(onClick = { selected = null }) { Text("Отмена") } })
                }
            } else Text(if (match.isCaptain) "Твой результат отправлен" else "Результат отправляют капитаны", color = Muted, fontSize = 12.sp)
        }
        if (match.status == "DISPUTED") Text("Результат требует проверки модератором: разные ответы капитанов или недостаточные доказательства. Новый поиск пока недоступен.", color = Red, fontSize = 13.sp)
    }
}

@Composable
private fun OnlineTeamPanel(title: String, players: List<OnlinePlayer>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        players.forEach { participant ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (participant.role == "BARON") "Барон · К" else participant.role, color = Muted, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                Text(participant.riotId, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (participant.ready) "✓" else "·", color = if (participant.ready) Accent else Muted)
            }
        }
    }
}

@Composable
internal fun MiniMetric(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier.background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
internal fun TeamPanel(title: String, entries: List<AssignedPlayer>, average: Int, color: Color, modifier: Modifier = Modifier) {
    PanelCard(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("~$average", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        entries.forEach { assigned ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(assigned.role.title, fontSize = 11.sp, color = Muted, modifier = Modifier.width(86.dp))
                Text(
                    assigned.player.nickname,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (assigned.onSecondary) StatusPill("2-я")
                Spacer(Modifier.width(6.dp))
                Text("${assigned.player.rating}", fontSize = 12.sp, color = color)
            }
        }
    }
}
