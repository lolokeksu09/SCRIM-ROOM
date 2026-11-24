package dev.lolokeksu.scrimroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lolokeksu.scrimroom.data.ClubFixture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CupBracket(fixtures:List<ClubFixture>,cupStatus:String){
    val semi1=fixtures.firstOrNull{it.stage=="SEMIFINAL_1"}
    val semi2=fixtures.firstOrNull{it.stage=="SEMIFINAL_2"}
    val final=fixtures.firstOrNull{it.stage=="FINAL"}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Полуфиналы",fontWeight=FontWeight.Bold,fontSize=13.sp,color=Muted)
        BracketFixture("Полуфинал 1",semi1)
        BracketFixture("Полуфинал 2",semi2)
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Text("↓",color=Accent,fontSize=22.sp)}
        Text("Финал",fontWeight=FontWeight.Bold,fontSize=13.sp,color=Muted)
        if(final!=null) BracketFixture("Финальная встреча",final)
        else {
            val a=semi1?.let{f->f.winner?.let{if(it==f.a)f.aName else f.bName}}
            val b=semi2?.let{f->f.winner?.let{if(it==f.a)f.aName else f.bName}}
            Column(Modifier.fillMaxWidth().background(Outline,RoundedCornerShape(8.dp)).padding(10.dp)){
                Text(a ?: "Победитель полуфинала 1")
                Text(b ?: "Победитель полуфинала 2")
                Text(if(cupStatus=="CANCELLED") "Кубок отменён" else "Финал ещё не назначен",color=Muted,fontSize=12.sp)
            }
        }
    }
}
@Composable
private fun BracketFixture(label:String,f:ClubFixture?){
    Column(Modifier.fillMaxWidth().background(Outline,RoundedCornerShape(8.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(label,color=Muted,fontSize=11.sp)
        if(f==null){Text("Данные встречи недоступны",fontSize=12.sp,color=Muted)}
        else{
            for((id,name) in listOf(f.a to f.aName,f.b to f.bName)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(name,Modifier.weight(1f),fontWeight=FontWeight.Medium,fontSize=14.sp)
                    if(f.winner==id) Text("Победа",color=Accent,fontSize=11.sp)
                }
            }
            Text(SimpleDateFormat("dd.MM HH:mm",Locale("ru")).format(Date(f.starts*1000))+" · "+clubStatus(f.status),fontSize=11.sp,color=Muted)
        }
    }
}
