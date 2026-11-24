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
