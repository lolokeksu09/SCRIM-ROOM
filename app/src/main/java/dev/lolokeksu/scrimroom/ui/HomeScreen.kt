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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
fun HomeScreen(state: ScrimUiState, onProfile: () -> Unit, onQueue: () -> Unit, onCommunity: () -> Unit, onClub: () -> Unit, onSettings: () -> Unit, onSessions: () -> Unit, onHistory: () -> Unit) {
    Page {
        Eyebrow("SCRIM ROOM / VENTRY")
        Headline("Игровой клуб", "Пользовательские матчи · Грандмастер и выше")
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
            Text(title,fontSize=21.sp,fontWeight=FontWeight.Bold)
            Text(when {
                match != null -> "Открой очередь, чтобы посмотреть составы и доступные действия."
                queue?.queued == true -> "Игроков в текущей очереди: ${queue.waiting}"
                approved -> "Выбери обычный подбор или запланированную игровую сессию."
                state.onlineStatus == "PENDING_REVIEW" -> "Модератор проверяет отправленные документы."
                state.onlineStatus == "REJECTED" -> "Проверь свою анкету и документы."
                else -> "Укажи Riot ID, две роли и добавь два скриншота. Для входа нужна проверка модератором."
            },color=Muted,fontSize=13.sp)
            PrimaryAction(if(match!=null) "Открыть матч" else if(approved) "Открыть очередь" else "Открыть профиль") { if(approved || match!=null) onQueue() else onProfile() }
        }
        Eyebrow("Играть")
        Column {
            HomeEntry("Расписание","Игровые окна","Открыть расписание",onSessions)
            HomeEntry("Команды и турниры","Составы и кубки","Открыть соревнования",onClub)
        }
        Eyebrow("Участник")
        Column {
            HomeEntry("Сообщество","Друзья и приглашения","Открыть сообщество",onCommunity)
            HomeEntry("Личная история","Матчи и рейтинг","Открыть историю",onHistory)
            HomeEntry("Настройки",state.inbox?.let { "Непрочитанных: ${it.count{item->!item.read}}" } ?: "Аккаунт и уведомления","Открыть настройки",onSettings)
        }
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
    Surface(onClick=onClick,color=Panel,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).semantics { contentDescription=action }) {
        Row(modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold)
                Text(description,color=Muted,fontSize=12.sp)
            }
            Text("›",fontSize=24.sp,color=Accent)
        }
    }
    HorizontalDivider(color=Outline)
}
