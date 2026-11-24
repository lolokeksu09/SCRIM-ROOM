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
