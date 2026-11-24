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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(state: ScrimUiState, vm: ScrimViewModel, onProfile: () -> Unit) {
    val demoTeamsRequester = remember { BringIntoViewRequester() }
    Page {
        Eyebrow("Подбор / закрытые матчи")
        Headline("Очередь 5 × 5", "Подбор по ролям и внутренней оценке силы. Демонстрация доступна отдельно ниже.")
        PanelCard {
            Eyebrow("Онлайн-очередь")
            when {
                state.serverUrl.isBlank() -> {
                    StatusPill("Сервер не настроен")
                    Text("Введи HTTPS-адрес сервера в профиле. Настоящего поиска без сервера нет.", color = Muted, fontSize = 13.sp)
                    PrimaryAction("Открыть профиль") { onProfile() }
                }
                !state.hasOnlineAccount -> {
                    StatusPill("Нет онлайн-заявки")
                    Text("Зарегистрируй Riot ID и отправь минимум два скриншота для проверки.", color = Muted, fontSize = 13.sp)
                    PrimaryAction("Подать заявку") { onProfile() }
                }
                state.onlineStatus != "APPROVED" -> {
                    StatusPill(when(state.onlineStatus) {
                        "PENDING_REVIEW" -> "На модерации"
                        "REJECTED" -> "Заявка отклонена"
                        else -> "Проверка статуса"
                    })
                    Text("До одобрения модератором вступить в очередь нельзя.", color = Muted, fontSize = 13.sp)
                    SecondaryAction("Обновить статус", enabled = !state.onlineBusy) { vm.syncOnline() }
                }
                else -> {
                    StatusPill("Допуск подтверждён", active = true)
                    val info = state.onlineQueue
                    val game = info?.match
                    if (info != null) {
                        Text("Отказы / пропуски готовности: ${info.incidents}", color = Muted, fontSize = 12.sp)
                        if (info.blockedUntil > 0L) {
                            Text("Поиск временно ограничен до " + SimpleDateFormat("HH:mm:ss", Locale("ru")).format(Date(info.blockedUntil * 1000)), color = Red, fontSize = 12.sp)
                        }
                    }
                    when {
                        game != null -> OnlineMatchCard(game, vm, state.onlineBusy)
                        info?.queued == true -> {
                            Text(if (info.sessionId != null) "Поиск в игровой сессии" else "Поиск запущен", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Игроков в текущей очереди: ${info.waiting}", color = Muted, fontSize = 13.sp)
                            Text("Поиск ждёт допустимое распределение всех ролей и баланс оценок. Матч может не появиться сразу даже при 10+ игроках.",color=Muted,fontSize=12.sp)
                            Text("Без связи с сервером поиск прекращается через 90 секунд. Для свёрнутого приложения включи наблюдение в настройках.",color=Muted,fontSize=12.sp)
                            SecondaryAction("Покинуть очередь", enabled = !state.onlineBusy) { vm.leaveOnlineQueue() }
                        }
                        else -> {
                            Text("Ожидаем 10 подтверждённых участников одного региона. Подбор учитывает две предпочитаемые роли и предварительную оценку силы.",
                                color = Muted, fontSize = 13.sp)
                            PrimaryAction("Найти пользовательский матч", enabled = !state.onlineBusy && (info?.blockedUntil ?: 0L) == 0L) { vm.joinOnlineQueue() }
                        }
                    }
                    SecondaryAction("Обновить", enabled = !state.onlineBusy) { vm.refreshQueue() }
                }
            }
        }
        HorizontalDivider(color = Outline)
        Eyebrow("Демонстрация / без сервера")
        PanelCard {
            StatusPill("Вымышленные игроки")
            Text("Демо-подбор никуда не отправляется и не влияет на реальные матчи.", color = Muted, fontSize = 13.sp)
            Text("${exampleQueue().size} тестовых участников", color = Accent, fontWeight = FontWeight.Bold)
            if (state.demoMatch == null) {
                SecondaryAction("Сформировать демо-матч") { vm.buildDemoMatch() }
            } else {
                StatusPill("Демо-матч сформирован", active = true)
                SecondaryAction("Перейти к составам") { vm.requestDemoTeams() }
            }
        }
        val match = state.demoMatch
        if (match != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniMetric("${match.teamAverageGap}", "Разница средних", Modifier.weight(1f))
                MiniMetric("${match.secondaryCount}", "Вторые роли", Modifier.weight(1f))
            }
            TeamPanel("Демо · синие", match.blue, match.blueAverage, Blue,
                modifier = Modifier.bringIntoViewRequester(demoTeamsRequester))
            LaunchedEffect(match, state.demoScrollRequest) {
                withFrameNanos { }
                demoTeamsRequester.bringIntoView()
            }
            TeamPanel("Демо · красные", match.red, match.redAverage, Red)
            PanelCard {
                Eyebrow("Локальный демо-результат")
                Text("Не связан с Wild Rift и не влияет на онлайн-очередь.", color = Muted, fontSize = 12.sp)
                Button(onClick = { vm.finishDemo(Side.BLUE) }, modifier = Modifier.fillMaxWidth()) { Text("Победили синие") }
                OutlinedButton(onClick = { vm.finishDemo(Side.RED) }, modifier = Modifier.fillMaxWidth()) { Text("Победили красные") }
            }
        }
    }
}
