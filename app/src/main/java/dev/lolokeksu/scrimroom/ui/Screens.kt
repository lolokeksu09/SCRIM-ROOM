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

private val Blue = Color(0xFF74B7EC)
private val Red = Color(0xFFFFA49F)

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) { content(); Spacer(Modifier.height(12.dp)) }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), color = Accent, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Headline(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Outline)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF10140A))
    ) { Text(text, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(49.dp),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text, color = Color.White) }
}

@Composable
private fun StatusPill(label: String, active: Boolean = false) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (active) Accent else Muted,
        modifier = Modifier.background(
            if (active) Color(0xFF23301D) else Color(0xFF242A2C),
            RoundedCornerShape(8.dp)
        ).padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
fun HomeScreen(state: ScrimUiState, onProfile: () -> Unit, onQueue: () -> Unit) {
    Page {
        Eyebrow("Независимые пользовательские матчи")
        Headline("Сильные игроки.\nЧестный подбор.", "Проектируем закрытые 5×5 матчи для сообщества Wild Rift. Это неофициальный инструмент организации игр.")
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Eyebrow("Доступ в клуб")
                    Text("Грандмастер и выше", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Текущий или предыдущий сезон", color = Muted, fontSize = 12.sp)
                }
                StatusPill(when (state.onlineStatus) {
                    "APPROVED" -> "Допущен"
                    "PENDING_REVIEW" -> "Модерация"
                    "REJECTED" -> "Отказано"
                    else -> "Не подтверждён"
                }, active = state.onlineStatus == "APPROVED")
            }
            HorizontalDivider(color = Outline)
            Text("Онлайн-допуск появляется только после проверки скриншотов модератором. Локальный профиль не подтверждает ранг.", color = Muted, fontSize = 12.sp)
            PrimaryAction("Заполнить профиль") { onProfile() }
        }
        PanelCard {
            Eyebrow("Локальный эксперимент")
            Text("Алгоритм подбора 5 × 5", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Двенадцать вымышленных участников, две предпочтительные роли и сравнение силы противоположных позиций.", color = Muted, fontSize = 13.sp)
            SecondaryAction("Проверить демо-подбор") { onQueue() }
        }
        PanelCard {
            Eyebrow("Состояние разработки")
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Metric("${state.demoHistory.size}", "Демо-матчей")
                Metric("${state.profile.screenshots.size}", "Скриншотов")
                Metric(if (state.onlineQueue?.match != null) "1" else "0", "Текущий матч")
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.Black, color = Accent)
        Text(label, fontSize = 10.sp, color = Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    title: String, options: List<T>, value: T, text: (T) -> String, onChoose: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == value,
                    onClick = { onChoose(option) },
                    label = { Text(text(option), fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
fun ProfileScreen(profile: PlayerProfile, state: ScrimUiState, vm: ScrimViewModel) {
    var riotId by rememberSaveable(profile.riotId) { mutableStateOf(profile.riotId) }
    var region by rememberSaveable(profile.region) { mutableStateOf(profile.region) }
    var currentRank by rememberSaveable(profile.currentRank) { mutableStateOf(profile.currentRank) }
    var previousRank by rememberSaveable(profile.previousRank) { mutableStateOf(profile.previousRank) }
    var primary by rememberSaveable(profile.primary) { mutableStateOf(profile.primary) }
    var secondary by rememberSaveable(profile.secondary) { mutableStateOf(profile.secondary) }
    var documents by rememberSaveable(profile.screenshots) { mutableStateOf(profile.screenshots) }
    var serverAddress by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var allowUpload by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) documents = (documents + vm.persistDocumentUris(uris)).distinct().take(4)
    }
    val eligible = currentRank.level >= Rank.GRANDMASTER.level || previousRank.level >= Rank.GRANDMASTER.level
    Page {
        Eyebrow("Участник / регистрация")
        Headline("Профиль игрока", "Два этапа: сохранить данные на устройстве и отдельно отправить заявку на пилотный сервер.")
        PanelCard {
            Eyebrow("Пилотный сервер")
            OutlinedTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it.take(200) },
                label = { Text("HTTPS адрес сервера") },
                placeholder = { Text("https://scrim.example.org") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Используй только адрес сервера проекта. При его смене старая привязка аккаунта сбрасывается.", color = Muted, fontSize = 12.sp)
            SecondaryAction("Сохранить адрес", enabled = !state.onlineBusy) { vm.saveServerUrl(serverAddress) }
            StatusPill(when(state.onlineStatus) {
                "APPROVED" -> "Онлайн: допущен"
                "PENDING_REVIEW" -> "Онлайн: ожидает модерации"
                "REJECTED" -> "Онлайн: отклонён"
                else -> "Онлайн: нет подтверждённой заявки"
            }, active = state.onlineStatus == "APPROVED")
            if (state.hasOnlineAccount) {
                Text("Скриншотов на сервере: ${state.onlineEvidenceCount}", color = Muted, fontSize = 12.sp)
                SecondaryAction("Проверить статус", enabled = !state.onlineBusy) { vm.syncOnline() }
            }
        }
        if (profile.verification == Verification.PENDING_REVIEW) {
            StatusPill(if (state.hasOnlineAccount) "Копия анкеты на устройстве" else "Черновик сохранён локально", active = true)
        }
        PanelCard {
            OutlinedTextField(
                value = riotId,
                onValueChange = { riotId = it.take(64) },
                label = { Text("Riot ID") },
                placeholder = { Text("Имя#Тег") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ChoiceRow("Игровой регион", listOf("EUROPE", "ASIA", "AMERICAS"), region, { it }) { region = it }
            ChoiceRow("Текущий сезон", Rank.entries, currentRank, { it.title }) { currentRank = it }
            ChoiceRow("Предыдущий сезон", Rank.entries, previousRank, { it.title }) { previousRank = it }
            if (!eligible) {
                Text("Нужен Грандмастер+ хотя бы в одном из двух сезонов.", color = Color(0xFFFFA49F), fontSize = 12.sp)
            }
        }
        PanelCard {
            ChoiceRow("Основная роль", Role.entries, primary, { it.title }) { chosen ->
                primary = chosen
                if (secondary == chosen) secondary = Role.entries.first { it != chosen }
            }
            ChoiceRow("Вторая роль", Role.entries, secondary, { it.title }) { chosen ->
                secondary = chosen
                if (primary == chosen) primary = Role.entries.first { it != chosen }
            }
        }
        PanelCard {
            Eyebrow("Подтверждение статистики")
            Text("Добавь минимум два скриншота", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("1. Статистика рангового сезона. 2. Статистика чемпионов. Формат PNG, JPEG или WEBP, до 4 МБ каждый.", fontSize = 12.sp, color = Muted)
            Text("Выбрано файлов: ${documents.size}", color = Accent, fontWeight = FontWeight.Bold)
            SecondaryAction("Выбрать изображения") { launcher.launch(arrayOf("image/*")) }
            if (documents.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Документы только на устройстве", active = true)
                }
                SecondaryAction("Очистить список изображений") { documents = emptyList() }
            }
        }
        SecondaryAction("Сохранить локальный черновик") {
            vm.register(
                PlayerProfile(
                    riotId = riotId, region = region,
                    currentRank = currentRank, previousRank = previousRank,
                    primary = primary, secondary = secondary,
                    screenshots = documents
                )
            )
        }
        PanelCard {
            Eyebrow("Отправка заявки")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowUpload, onCheckedChange = { allowUpload = it })
                Text("Разрешаю передать Riot ID, ранги, роли и выбранные изображения администратору пилотного сервера для проверки.",
                    modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp)
            }
            PrimaryAction("Отправить заявку и скриншоты", enabled = allowUpload && !state.onlineBusy && state.serverUrl.isNotBlank()) {
                vm.submitOnline(PlayerProfile(
                    riotId = riotId, region = region,
                    currentRank = currentRank, previousRank = previousRank,
                    primary = primary, secondary = secondary,
                    screenshots = documents
                ))
            }
            Text("Нажатие отправит документы на настроенный сервер. Не отправляй личные данные, не относящиеся к игре. Возврат или обновление анкеты может потребовать повторной модерации.",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(state: ScrimUiState, vm: ScrimViewModel, onProfile: () -> Unit) {
    val demoTeamsRequester = remember { BringIntoViewRequester() }
    val onlineAvailable = state.serverUrl.isNotBlank() && state.hasOnlineAccount
    LaunchedEffect(onlineAvailable) {
        while (isActive && onlineAvailable) {
            vm.refreshQueue()
            delay(10000L)
        }
    }
    Page {
        Eyebrow("Подбор / закрытые матчи")
        Headline("Очередь 5 × 5", "Онлайн-матчи на пилотном сервере и отдельный локальный деморежим.")
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
                    when {
                        game != null -> OnlineMatchCard(game, vm, state.onlineBusy)
                        info?.queued == true -> {
                            Text("Поиск запущен", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Игроков в ожидании в этом регионе: ${info.waiting}", color = Muted, fontSize = 13.sp)
                            SecondaryAction("Покинуть очередь", enabled = !state.onlineBusy) { vm.leaveOnlineQueue() }
                        }
                        else -> {
                            Text("Ожидаем 10 подтверждённых участников одного региона. Подбор учитывает две предпочитаемые роли и предварительную оценку силы.",
                                color = Muted, fontSize = 13.sp)
                            PrimaryAction("Найти пользовательский матч", enabled = !state.onlineBusy) { vm.joinOnlineQueue() }
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

@Composable
private fun OnlineMatchCard(match: OnlineMatch, vm: ScrimViewModel, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when(match.status) { "READY_CHECK" -> "Матч найден · подтверждение"; "DISPUTED" -> "Результат оспаривается"; else -> "Матч подтверждён" },
            fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Готово игроков: ${match.readyCount}/10", color = Accent, fontSize = 13.sp)
        if (match.status == "READY_CHECK") {
            Text("Подтверди готовность в течение трёх минут после создания матча. Если не все ответят, матч отменится.", color = Muted, fontSize = 12.sp)
            PrimaryAction("Я готов", enabled = !busy) { vm.readyOnlineMatch(match.id) }
            SecondaryAction("Отказаться", enabled = !busy) { vm.declineOnlineMatch(match.id) }
        }
        OnlineTeamPanel("Синяя команда", match.blue, Blue)
        OnlineTeamPanel("Красная команда", match.red, Red)
        if (match.status == "ACTIVE") {
            Text("Создайте пользовательское лобби вручную. После игры капитаны барон-линии сообщают победителя.", color = Muted, fontSize = 12.sp)
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
        if (match.status == "DISPUTED") Text("Капитаны указали разных победителей. Матч ожидает модерации; новый поиск пока недоступен.", color = Red, fontSize = 13.sp)
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
private fun MiniMetric(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier.background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun TeamPanel(title: String, entries: List<AssignedPlayer>, average: Int, color: Color, modifier: Modifier = Modifier) {
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

@Composable
fun HistoryScreen(results: List<DemoResult>, state: ScrimUiState, vm: ScrimViewModel) {
    LaunchedEffect(state.hasOnlineAccount) { if (state.hasOnlineAccount) vm.loadOnlineResults() }
    Page {
        if (state.hasOnlineAccount) {
            Eyebrow("Подтверждённые матчи")
            PanelCard {
                SecondaryAction("Обновить результаты", enabled = !state.onlineBusy) { vm.loadOnlineResults() }
                val online = state.onlineResults
                if (online == null) Text("Результаты ещё не загружены", color = Muted, fontSize = 13.sp)
                else if (online.isEmpty()) Text("Нет подтверждённых матчей", color = Muted, fontSize = 13.sp)
                else online.forEach { result ->
                    Text(when { result.winner == "VOID" -> "Аннулирован"; result.winner == result.side -> "Победа"; else -> "Поражение" }, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(result.resolvedAt * 1000)), color = Muted, fontSize = 12.sp)
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
