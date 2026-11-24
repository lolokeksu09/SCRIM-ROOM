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
fun HistoryScreen(results: List<DemoResult>, state: ScrimUiState, vm: ScrimViewModel) {
    LaunchedEffect(state.hasOnlineAccount) { if (state.hasOnlineAccount) vm.loadOnlineResults() }
    Page {
        if (state.hasOnlineAccount) {
            Headline("Статистика клуба","Только подтверждённые результаты SCRIM ROOM")
            state.personalStats?.let { stats ->
                PanelCard {
                    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Metric(stats.played.toString(),"Матчи")
                        Metric(stats.wins.toString(),"Победы")
                        Metric(stats.winRate?.let { "$it%" } ?: "—","Винрейт")
                    }
                    Text("Поражения: ${stats.losses} · Аннулировано: ${stats.voids}",color=Muted,fontSize=12.sp)
                    Text("Калибровка: ${stats.calibrated}/${stats.target} подтверждённых игр",fontWeight=FontWeight.Bold)
                    Text(if(stats.calibrated<stats.target) "Предварительная оценка; выборка пока мала." else "Начальная выборка собрана. Оценка не является Riot MMR.",color=Muted,fontSize=12.sp)
                    if(stats.played>0) WinLossStrip(stats.wins,stats.losses)
                    HorizontalDivider(color=Outline)
                    Eyebrow("По фактическим ролям")
                    Text("Оценки основаны на последних 30 подтверждённых играх; неопределённость — показатель размера выборки, не доверительный интервал.",color=Muted,fontSize=12.sp)
                    stats.roles.forEach { role ->
                        Text("${Role.entries.firstOrNull { it.name==role.role }?.title ?: role.role}: ${role.played} игр · ${role.wins} побед · ${role.losses} поражений",fontSize=13.sp)
                        if(role.played>0) WinLossStrip(role.wins,role.losses)
                        if(role.estimate!=null) Text("Оценка для подбора: ${role.estimate} · Выборка: ${role.sample ?: 0} · Неопределённость: ${role.uncertainty ?: 0}",color=Muted,fontSize=11.sp)
                    }
                }
            }
            state.onlineResults?.let { RatingHistoryChart(it) }
            Eyebrow("Подтверждённые матчи")
            PanelCard {
                SecondaryAction("Обновить результаты", enabled = !state.onlineBusy) { vm.loadOnlineResults() }
                val online = state.onlineResults
                if (online == null) Text("Результаты ещё не загружены", color = Muted, fontSize = 13.sp)
                else if (online.isEmpty()) Text("Нет подтверждённых матчей", color = Muted, fontSize = 13.sp)
                else online.forEach { result ->
                    Text(when { result.winner == "VOID" -> "Аннулирован"; result.winner == result.side -> "Победа"; else -> "Поражение" }, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(result.resolvedAt * 1000)), color = Muted, fontSize = 12.sp)
                    if(result.beforeRating!=null && result.afterRating!=null) { val delta=result.afterRating-result.beforeRating; Text("Elo: ${result.afterRating} (${if(delta>0) "+" else ""}$delta)",color=if(delta>=0) Accent else Red,fontSize=13.sp) }
                    if(result.role.isNotBlank()) Text("Роль: " + (Role.entries.firstOrNull { it.name==result.role }?.title ?: result.role),color=Muted,fontSize=12.sp)
                    HorizontalDivider(color = Outline)
                }
            }
        }
        Eyebrow("История / только демо")
        Headline("Матчи", "Результаты тестовых составов хранятся локально на устройстве. Это не официальная статистика игры.")
        if (results.isEmpty()) {
            PanelCard {
                Text("История пуста", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Сформируй демо-матч в разделе «Очередь» и выбери победителя, чтобы появилась запись.", color = Muted, fontSize = 13.sp)
            }
        } else {
            results.forEach { result ->
                PanelCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Демо · ${if (result.winner == Side.BLUE) "синие" else "красные"}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(result.id)), color = Muted, fontSize = 11.sp)
                        }
                        StatusPill("Локально", active = true)
                    }
                    Text("Средняя сила: ${result.blueAverage} / ${result.redAverage}", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
