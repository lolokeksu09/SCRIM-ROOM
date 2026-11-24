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
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF10140A))
    ) { Text(text, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text) }
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
fun HomeScreen(state: ScrimUiState, onProfile: () -> Unit, onQueue: () -> Unit, onCommunity: () -> Unit, onClub: () -> Unit, onSettings: () -> Unit, onSessions: () -> Unit, onHistory: () -> Unit) {
    Page {
        Eyebrow("SCRIM ROOM / VENTRY")
        Headline("Сильные игроки.\nЧестный подбор.", "Закрытые пользовательские матчи Wild Rift для Грандмастеров и выше.")
        val queue = state.onlineQueue
        val match = queue?.match
        val approved = state.onlineStatus == "APPROVED"
        PanelCard {
            Eyebrow(if (approved) "Твой игровой статус" else "Вступление в клуб")
            val title = when {
                match?.status == "READY_CHECK" -> "Матч найден"
                match?.status == "ACTIVE" -> "Матч назначен"
                match?.status == "DISPUTED" -> "Результат на проверке"
                queue?.queued == true -> "Поиск запущен"
                approved -> "Готов к игре"
                state.onlineStatus == "PENDING_REVIEW" -> "Анкета на проверке"
                state.onlineStatus == "REJECTED" -> "Заявка отклонена"
                state.hasOnlineAccount -> "Проверка аккаунта"
                else -> "Грандмастер и выше"
            }
            Text(title,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(when {
                match != null -> "Открой очередь, чтобы посмотреть составы и доступные действия."
                queue?.queued == true -> "Игроков в текущей очереди: ${queue.waiting}"
                approved -> "Выбери обычный подбор или запланированную игровую сессию."
                state.onlineStatus == "PENDING_REVIEW" -> "Модератор проверяет отправленные документы."
                state.onlineStatus == "REJECTED" -> "Проверь свою анкету и документы."
                else -> "Укажи Riot ID, две роли и добавь два скриншота. Для входа нужна проверка модератором."
            },color=Muted,fontSize=13.sp)
            PrimaryAction(if(approved) "Открыть очередь" else "Открыть профиль") { if(approved) onQueue() else onProfile() }
            if(approved) SecondaryAction("Игровые сессии"){onSessions()}
        }
        Eyebrow("Играть")
        HomeEntry("Расписание","Предварительная запись на игровые окна.","Открыть расписание",onSessions)
        HomeEntry("Команды и турниры","Постоянные составы, сезонные встречи и кубки.","Открыть соревнования",onClub)
        Eyebrow("Участник")
        HomeEntry("Сообщество","Друзья, приглашения и обращения к модератору.","Открыть сообщество",onCommunity)
        HomeEntry("Личная история","Подтверждённые матчи и статистика по ролям.","Открыть историю",onHistory)
        HomeEntry("Настройки","Восстановление доступа, приватность и уведомления.","Открыть настройки",onSettings)
        if(!approved) {
            Eyebrow("Познакомиться с подбором")
            Text("Без аккаунта можно посмотреть отдельную демонстрацию на вымышленных игроках.",color=Muted,fontSize=12.sp)
            SecondaryAction("Проверить демо-подбор"){onQueue()}
        }
        Text("Независимый клуб. Не связан с Riot Games, не заменяет рейтинговую очередь.",color=Muted,fontSize=11.sp)
    }
}

@Composable
private fun HomeEntry(title:String,description:String,action:String,onClick:()->Unit) {
    Surface(shape=RoundedCornerShape(12.dp),color=Panel,modifier=Modifier.fillMaxWidth()) {
        Column(modifier=Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text(description,color=Muted,fontSize=12.sp)
            TextButton(onClick=onClick,modifier=Modifier.align(Alignment.End)){Text(action)}
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
        Headline("Профиль игрока", "Укажи игровые данные и отправь заявку для допуска в клуб.")
        PanelCard {
            Eyebrow("Подключение к клубу")
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
        if (state.hasOnlineAccount && state.onlineEvidenceCount > 0) {
            PanelCard {
                Eyebrow("Распознавание статистики")
                Text("OCR уже отправленных документов. Подсказки не подтверждают ранг или личность.", color = Muted, fontSize = 12.sp)
                (1..state.onlineEvidenceCount.coerceAtMost(4)).forEach { slot ->
                    SecondaryAction("Распознать скриншот $slot", enabled = !state.onlineBusy) { vm.recognizeEvidence(slot) }
                }
                state.ocrSuggestions?.let { result ->
                    Text("Документ №${result.slot} · не проверено", color = Accent, fontSize = 12.sp)
                    Text("Матчи: ${result.matches ?: "—"} · Винрейт: ${result.winRate?.let { "$it%" } ?: "—"} · KDA: ${result.kda ?: "—"}", fontSize = 13.sp)
                    val candidate = result.ranks.singleOrNull()?.let { name -> Rank.entries.firstOrNull { it.name == name } }
                    if (candidate != null) {
                        Text("Найденный ранг: ${candidate.title}. Проверь, к какому сезону он относится.", fontSize = 12.sp)
                        SecondaryAction("Подставить в текущий сезон") { currentRank = candidate }
                        SecondaryAction("Подставить в прошлый сезон") { previousRank = candidate }
                    } else if (result.ranks.isNotEmpty()) {
                        Text("Найдено несколько рангов. Выбери вручную по оригиналу.", color = Muted, fontSize = 12.sp)
                    }
                }
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

@Composable
fun SessionsScreen(state: ScrimUiState, vm: ScrimViewModel, onProfile: () -> Unit, onQueue: () -> Unit) {
    LaunchedEffect(state.hasOnlineAccount, state.serverUrl) {
        if (state.hasOnlineAccount) {
            while (isActive) {
                vm.loadSessions()
                delay(15000L)
            }
        }
    }
    Page {
        Eyebrow("Календарь / закрытые игры")
        Headline("Игровые сессии", "Запишись заранее и войди в подбор в назначенное игровое окно. Запись не гарантирует матч.")
        if (!state.hasOnlineAccount) {
            PanelCard {
                Text("Для расписания нужна онлайн-заявка.", color = Muted)
                PrimaryAction("Открыть профиль") { onProfile() }
            }
        } else {
            SecondaryAction("Обновить расписание", enabled = !state.onlineBusy) { vm.loadSessions() }
            if (state.onlineStatus != "APPROVED") Text("Запись доступна только допущенным игрокам.", color = Muted)
            val sessions = state.sessions
            if (sessions == null) Text("Расписание ещё не загружено", color = Muted)
            else if (sessions.isEmpty()) Text("Нет запланированных сессий в твоём регионе", color = Muted)
            else sessions.forEach { event ->
                PanelCard {
                    Eyebrow(if (event.status == "OPEN") "СЕССИЯ ОТКРЫТА" else "ПРЕДСТОЯЩАЯ")
                    Text(event.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
                    Text(format.format(Date(event.startsAt * 1000)) + " — " + format.format(Date(event.endsAt * 1000)), color = Muted, fontSize = 13.sp)
                    Text("Регион: ${event.region} · Записано: ${event.registered}/${event.capacity}", fontSize = 13.sp)
                    Text("Основные роли: " + Role.entries.joinToString(" · ") { "${it.title} ${event.roles[it.name] ?: 0}" }, color = Muted, fontSize = 12.sp)
                    Text("В очереди сейчас: ${event.waiting}", color = Accent, fontSize = 13.sp)
                    if (event.enrolled) {
                        StatusPill("Ты записан", active = true)
                        if (event.status == "OPEN") {
                            val queued = state.onlineQueue
                            val alreadyHere = queued?.queued == true && queued.sessionId == event.id
                            if (alreadyHere || queued?.match != null) {
                                PrimaryAction("Перейти к очереди") { onQueue() }
                            } else {
                                PrimaryAction("Войти в очередь сессии", enabled = !state.onlineBusy && state.onlineStatus == "APPROVED" && queued?.queued != true && (queued?.blockedUntil ?: 0L) == 0L) {
                                    vm.joinOnlineQueue(event.id)
                                    onQueue()
                                }
                            }
                        }
                        SecondaryAction("Отменить запись", enabled = !state.onlineBusy && state.onlineQueue?.match == null) { vm.changeSession(event.id, false) }
                    } else {
                        PrimaryAction("Записаться", enabled = !state.onlineBusy && state.onlineStatus == "APPROVED" && event.registered < event.capacity) { vm.changeSession(event.id, true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineMatchCard(match: OnlineMatch, vm: ScrimViewModel, busy: Boolean) {
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
            Eyebrow("Личная статистика клуба")
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
                    HorizontalDivider(color=Outline)
                    Eyebrow("По фактическим ролям")
                    Text("Оценки основаны на последних 30 подтверждённых играх; неопределённость — показатель размера выборки, не доверительный интервал.",color=Muted,fontSize=12.sp)
                    stats.roles.forEach { role ->
                        Text("${Role.entries.firstOrNull { it.name==role.role }?.title ?: role.role}: ${role.played} игр · ${role.wins} побед · ${role.losses} поражений",fontSize=13.sp)
                        if(role.estimate!=null) Text("Оценка для подбора: ${role.estimate} · Выборка: ${role.sample ?: 0} · Неопределённость: ${role.uncertainty ?: 0}",color=Muted,fontSize=11.sp)
                    }
                }
            }
            Eyebrow("Подтверждённые матчи")
            PanelCard {
                SecondaryAction("Обновить результаты", enabled = !state.onlineBusy) { vm.loadOnlineResults() }
                val online = state.onlineResults
                if (online == null) Text("Результаты ещё не загружены", color = Muted, fontSize = 13.sp)
                else if (online.isEmpty()) Text("Нет подтверждённых матчей", color = Muted, fontSize = 13.sp)
                else online.forEach { result ->
                    Text(when { result.winner == "VOID" -> "Аннулирован"; result.winner == result.side -> "Победа"; else -> "Поражение" }, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(result.resolvedAt * 1000)), color = Muted, fontSize = 12.sp)
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


@Composable
fun CompetitiveScreen(state: ScrimUiState,vm: ScrimViewModel) {
    var section by rememberSaveable { mutableStateOf("Команда") }
    var teamName by rememberSaveable { mutableStateOf("") }
    var riotId by rememberSaveable { mutableStateOf("") }
    var opponent by rememberSaveable { mutableStateOf("") }
    var seasonId by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<String,()->Unit>?>(null) }
    val formatter=SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("ru"))
    LaunchedEffect(state.onlineStatus,state.serverUrl) { if(state.onlineStatus=="APPROVED") vm.loadClub() }
    Page {
        Eyebrow("Соревновательная инфраструктура")
        Headline("Команды и турниры","Постоянные составы и запланированные встречи. Отдельно от индивидуальной очереди и Elo.")
        if(state.onlineStatus!="APPROVED") {
            PanelCard { Text("Соревнования доступны после допуска игрока.",color=Muted) }
        } else {
            SecondaryAction("Обновить соревнования",enabled=!state.onlineBusy){vm.loadClub()}
            ChoiceRow("Раздел",listOf("Команда","Встречи","Кубки","Сезоны"),section,{it}){section=it}
            val data=state.club
            if(data==null) Text("Данные ещё не загружены",color=Muted)
            else {
                val mine=data.mine
                if(section=="Команда"){
                    if(mine==null){
                        PanelCard {
                            OutlinedTextField(teamName,{teamName=it.take(48)},label={Text("Название команды")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            PrimaryAction("Создать команду",enabled=!state.onlineBusy && teamName.trim().length>=3){vm.createClubTeam(teamName.trim())}
                        }
                        data.invites.forEach { i ->
                            PanelCard {
                                Text("Приглашение: ${i.name}",fontWeight=FontWeight.Bold)
                                PrimaryAction("Вступить",enabled=!state.onlineBusy){vm.clubInviteAction(i.id,true)}
                                SecondaryAction("Отклонить",enabled=!state.onlineBusy){vm.clubInviteAction(i.id,false)}
                            }
                        }
                    } else {
                        PanelCard {
                            Text(mine.name,fontSize=22.sp,fontWeight=FontWeight.Bold)
                            Text("${mine.members.size}/5 участников · ${mine.wins} побед из ${mine.played} встреч",color=Accent)
                            if(mine.locked) StatusPill("Состав зафиксирован")
                            mine.members.forEach { member ->
                                Text(member.riotId+(if(member.id==mine.captain) " · капитан" else ""),fontWeight=FontWeight.Medium)
                                Text(Role.entries.firstOrNull { it.name==member.role }?.title ?: member.role,color=Muted,fontSize=12.sp)
                                if(data.captain && member.id!=mine.captain && !mine.locked){
                                    SecondaryAction("Передать капитанство",enabled=!state.onlineBusy){confirmation="Передать управление ${member.riotId}?" to {vm.clubMemberAction(member.id,true)}}
                                    SecondaryAction("Исключить участника",enabled=!state.onlineBusy){confirmation="Исключить ${member.riotId}?" to {vm.clubMemberAction(member.id,false)}}
                                }
                            }
                            if(data.captain && mine.members.size<5 && !mine.locked){
                                OutlinedTextField(riotId,{riotId=it.take(64)},label={Text("Riot ID участника")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                PrimaryAction("Пригласить в команду",enabled=!state.onlineBusy && riotId.length>=5 && riotId.contains("#")){vm.inviteClubMember(riotId.trim())}
                            }
                            SecondaryAction("Покинуть команду",enabled=!state.onlineBusy && !mine.locked && (!data.captain || mine.members.size==1)){confirmation="Покинуть команду?" to {vm.leaveClubTeam()}}
                        }
                    }
                    Eyebrow("Команды региона")
                    data.teams.forEach { team ->
                        PanelCard {
                            Text(team.name,fontWeight=FontWeight.Bold)
                            Text("${team.played} встреч · ${team.wins} побед",color=Muted)
                            StatusPill(if(team.eligible) "Полный состав" else "Набор игроков",active=team.eligible)
                        }
                    }
                }
                if(section=="Встречи"){
                    if(data.captain && mine?.eligible==true && !mine.locked){
                        val now=System.currentTimeMillis()/1000
                        val seasons=data.seasons.filter { it.ends>now+60 }
                        val opponents=data.teams.filter { it.id!=mine.id && it.eligible && !it.locked }
                        PanelCard {
                            Eyebrow("Командный вызов")
                            if(seasons.isEmpty() || opponents.isEmpty()) Text("Нужны сезон и свободная команда с полным составом.",color=Muted)
                            else {
                                ChoiceRow("Сезон",seasons.map{it.id},seasonId,{id->seasons.first{it.id==id}.name}){seasonId=it}
                                ChoiceRow("Соперник",opponents.map{it.id},opponent,{id->opponents.first{it.id==id}.name}){opponent=it}
                                OutlinedTextField(start,{start=it.take(16)},label={Text("ДД.ММ.ГГГГ ЧЧ:ММ")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                Text("Время устройства. Капитан соперника должен принять вызов.",color=Muted,fontSize=12.sp)
                                val parsed=runCatching { SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("ru")).apply{isLenient=false}.parse(start)?.time?.div(1000) }.getOrNull()
                                PrimaryAction("Отправить вызов",enabled=!state.onlineBusy && start.length==16 && parsed!=null && parsed>now+60 && seasons.any{it.id==seasonId} && opponents.any{it.id==opponent}){
                                    parsed?.let{vm.createClubChallenge(opponent,seasonId,it)}
                                }
                            }
                        }
                    }
                    data.fixtures.filter { it.cupId==null }.forEach { f ->
                        PanelCard {
                            Text("${f.aName} — ${f.bName}",fontWeight=FontWeight.Bold)
                            Text(formatter.format(Date(f.starts*1000)),color=Muted)
                            Text(clubStatus(f.status),color=Accent)
                            f.winner?.let { Text("Победитель: "+if(it==f.a) f.aName else f.bName) }
                            if(data.captain && mine!=null && f.status=="PENDING" && mine.id in listOf(f.a,f.b)){
                                if(mine.id==f.b) PrimaryAction("Принять вызов",enabled=!state.onlineBusy){vm.clubChallengeAction(f.id,true)}
                                SecondaryAction("Отклонить / отменить вызов",enabled=!state.onlineBusy){vm.clubChallengeAction(f.id,false)}
                            }
                        }
                    }
                }
                if(section=="Сезоны"){
                    if(data.seasons.isEmpty()) Text("Сезоны ещё не созданы",color=Muted)
                    data.seasons.forEach { season ->
                        PanelCard {
                            Text(season.name,fontSize=20.sp,fontWeight=FontWeight.Bold)
                            Text(formatter.format(Date(season.starts*1000))+" — "+formatter.format(Date(season.ends*1000)),color=Muted,fontSize=12.sp)
                            val table=data.standings[season.id].orEmpty()
                            if(table.isEmpty()) Text("Нет подтверждённых встреч",color=Muted)
                            table.forEach { row -> Text("${row.name} · ${row.wins} побед / ${row.losses} поражений",fontSize=13.sp) }
                        }
                    }
                }
                if(section=="Кубки"){
                    if(data.cups.isEmpty()) Text("Турниры ещё не запланированы",color=Muted)
                    data.cups.forEach { cup ->
                        PanelCard {
                            Text(cup.name,fontSize=20.sp,fontWeight=FontWeight.Bold)
                            Text(clubStatus(cup.status),color=Accent)
                            data.fixtures.filter{it.cupId==cup.id}.sortedBy{when(it.stage){"SEMIFINAL_1"->0;"SEMIFINAL_2"->1;else->2}}.forEach { f ->
                                Eyebrow(if(f.stage=="FINAL") "Финал" else if(f.stage=="SEMIFINAL_1") "Полуфинал 1" else "Полуфинал 2")
                                Text("${f.aName} — ${f.bName}",fontWeight=FontWeight.Bold)
                                Text(formatter.format(Date(f.starts*1000))+" · "+clubStatus(f.status),color=Muted,fontSize=12.sp)
                                f.winner?.let{Text("Победитель: "+if(it==f.a) f.aName else f.bName,color=Accent)}
                            }
                            cup.winner?.let { winner -> Text("Чемпион: "+(data.teams.firstOrNull{it.id==winner}?.name ?: data.fixtures.firstOrNull{it.cupId==cup.id && it.stage=="FINAL"}?.let{if(it.a==winner)it.aName else it.bName} ?: "Команда"),fontWeight=FontWeight.Bold,color=Accent) }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let{ (message,action) ->
        AlertDialog(onDismissRequest={confirmation=null},title={Text(message)},confirmButton={TextButton(onClick={confirmation=null;action()},enabled=!state.onlineBusy){Text("Подтвердить")}},dismissButton={TextButton(onClick={confirmation=null}){Text("Отмена")}})
    }
}

private fun clubStatus(status:String)=when(status){
    "PENDING"->"Ожидает капитана";"SCHEDULED"->"Запланирована";"FINISHED"->"Завершено";"RUNNING"->"Турнир идёт";"EXPIRED"->"Время истекло";"CANCELLED"->"Отменено";else->status
}


@Composable
fun AccountSettingsScreen(state:ScrimUiState,vm:ScrimViewModel,onProfile:()->Unit,onIntro:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var input by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf("")}
    val watcher by dev.lolokeksu.scrimroom.QueueWatchService.running.collectAsState()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted ->
        if(granted) dev.lolokeksu.scrimroom.QueueWatchService.start(context)
    }
    LaunchedEffect(state.hasOnlineAccount,state.serverUrl){if(state.hasOnlineAccount)vm.loadAccountSettings()}
    Page {
        Eyebrow("Управление аккаунтом")
        Headline("Настройки аккаунта","Безопасность, приватность и уведомления клуба.")
        SecondaryAction("О SCRIM ROOM"){onIntro()}
        PanelCard {
            Eyebrow("Восстановление доступа")
            if(state.hasOnlineAccount){
                Text("Создай одноразовый код и сохрани в надёжном месте вне приложения. Новый код отменяет предыдущий. Восстановление отключает старый токен.",color=Muted,fontSize=12.sp)
                PrimaryAction("Создать код восстановления",enabled=!state.onlineBusy){confirm="issue"}
                SecondaryAction("Отключить аккаунт",enabled=!state.onlineBusy){confirm="disconnect"}
            }else{
                Text("Сначала укажи HTTPS-сервер проекта. Для восстановления нужен ранее сохранённый код.",color=Muted,fontSize=12.sp)
                if(state.serverUrl.isBlank()) SecondaryAction("Настроить сервер"){onProfile()}
                OutlinedTextField(input,{input=it.take(43)},label={Text("Код восстановления")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                PrimaryAction("Восстановить доступ",enabled=!state.onlineBusy && state.serverUrl.isNotBlank() && input.trim().length==43){val code=input.trim();input="";vm.recoverAccount(code)}
            }
        }
        if(state.hasOnlineAccount){
            PanelCard {
                Eyebrow("Приватность")
                Text("Разрешать новым игрокам находить меня по точному Riot ID и отправлять запросы в друзья или команду. Уже существующие связи и история матчей сохраняются.",color=Muted,fontSize=12.sp)
                state.discoverable?.let { value ->
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=value,onCheckedChange={vm.setDiscoverable(it)},enabled=!state.onlineBusy);Text("Разрешить новые контакты")}
                } ?: Text("Настройки ещё не загружены",color=Muted)
            }
            PanelCard {
                Eyebrow("Фоновое наблюдение")
                Text("Включается вручную во время поиска или подтверждения. Проверяет очередь через HTTPS каждые 5 секунд при свёрнутом приложении, пока Android разрешает работу службы. Это не push.",color=Muted,fontSize=12.sp)
                if(watcher) SecondaryAction("Остановить наблюдение"){dev.lolokeksu.scrimroom.QueueWatchService.stop(context)}
                else PrimaryAction("Включить наблюдение",enabled=state.onlineQueue?.queued==true || state.onlineQueue?.match?.status=="READY_CHECK"){
                    if(android.os.Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else dev.lolokeksu.scrimroom.QueueWatchService.start(context)
                }
            }
            PanelCard {
                Eyebrow("Центр уведомлений")
                SecondaryAction("Обновить уведомления",enabled=!state.onlineBusy){vm.loadAccountSettings()}
                val items=state.inbox
                if(items==null) Text("Уведомления ещё не загружены",color=Muted)
                else {
                    Text("Непрочитанных: ${items.count{!it.read}}",color=Accent)
                    SecondaryAction("Отметить всё прочитанным",enabled=!state.onlineBusy && items.any{!it.read}){vm.readInbox()}
                    items.forEach { item ->
                        Text(item.title+(if(!item.read) " · Новое" else ""),fontWeight=FontWeight.Bold)
                        Text(item.body,fontSize=13.sp,color=Muted)
                        HorizontalDivider(color=Outline)
                    }
                }
            }
        }
    }
    if(confirm.isNotEmpty()) AlertDialog(onDismissRequest={confirm=""},title={Text(if(confirm=="issue")"Заменить код восстановления?" else "Отключить аккаунт?")},text={Text(if(confirm=="issue")"Старый код перестанет работать." else "Локальный токен будет удалён. Для возвращения нужен код восстановления. Поиск и назначенные матчи на сервере не отменяются.")},confirmButton={TextButton(onClick={val action=confirm;confirm="";if(action=="issue")vm.issueRecovery() else vm.disconnectAccount()}){Text("Подтвердить")}},dismissButton={TextButton(onClick={confirm=""}){Text("Отмена")}})
    state.recoveryCode?.let { code ->
        AlertDialog(onDismissRequest={vm.hideRecovery()},title={Text("Сохрани код вне приложения")},text={Column{
            androidx.compose.foundation.text.selection.SelectionContainer{Text(code,fontWeight=FontWeight.Bold)}
            Text("Код показан один раз. Не передавай его другим людям.",color=Muted,fontSize=12.sp)
        }},confirmButton={TextButton(onClick={vm.hideRecovery()}){Text("Сохранено")}},properties=androidx.compose.ui.window.DialogProperties(securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn))
    }
}
