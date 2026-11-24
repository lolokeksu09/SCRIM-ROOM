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
                if(items==null) Text(if(state.onlineBusy) "Загрузка уведомлений…" else "Уведомления ещё не загружены. Обнови список.",color=Muted)
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
