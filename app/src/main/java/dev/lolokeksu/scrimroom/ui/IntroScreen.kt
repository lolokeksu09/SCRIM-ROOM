package dev.lolokeksu.scrimroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IntroScreen(onFinish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Закрытый клуб", "Матчи и роли", "Твой клуб")
    val subtitles = listOf(
        "SCRIM ROOM — независимый продукт Ventry для организации пользовательских игр Wild Rift.",
        "Подбор двух команд из десяти допущенных игроков. Подтверждай готовность, входи в лобби и сохраняй результат.",
        "Записывайся на игровые сессии, создавай постоянную команду и участвуй в сезонных кубках."
    )
    val details = listOf(
        listOf("Грандмастер и выше", "Два скриншота для заявки", "Допуск после проверки модератором"),
        listOf("Пять ролей в каждой команде", "Оценка только внутри клуба", "Спорный результат проверяет модератор"),
        listOf("Друзья и приглашения", "Личная статистика и дисциплина", "Восстановление доступа по секретному коду")
    )
    BackHandler(enabled=page>0){page--}
    Surface(modifier=Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(modifier=Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("VENTRY / SCRIM ROOM",fontWeight=FontWeight.Black,color=Accent,modifier=Modifier.weight(1f),fontSize=13.sp)
                TextButton(onClick=onFinish){Text("Пропустить")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.fillMaxWidth()){
                repeat(3){index->Box(Modifier.weight(1f).height(4.dp).background(if(index<=page)Accent else Outline,RoundedCornerShape(4.dp)))}
            }
            Text("0${page+1} / 03",color=Muted,fontSize=12.sp)
            Text(titles[page],style=MaterialTheme.typography.headlineLarge)
            Text(subtitles[page],style=MaterialTheme.typography.bodyMedium,color=Muted)
            Surface(shape=RoundedCornerShape(14.dp),color=Panel,modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    details[page].forEachIndexed{index,line->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Text("0${index+1}",color=Accent,fontWeight=FontWeight.Bold,fontSize=13.sp)
                            Text(line,modifier=Modifier.weight(1f),fontWeight=FontWeight.Medium)
                        }
                    }
                }
            }
            Text("Неофициальный сервис. Не связан с Riot Games и не изменяет официальный ранг.",color=Muted,fontSize=12.sp)
            Button(onClick={if(page<2)page++ else onFinish()},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(12.dp)){
                Text(if(page<2)"Далее" else "Начать",fontWeight=FontWeight.Bold)
            }
            if(page>0)OutlinedButton(onClick={page--},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Назад")}
        }
    }
}
