package dev.lolokeksu.scrimroom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lolokeksu.scrimroom.data.OnlinePlayer
import dev.lolokeksu.scrimroom.model.Role

@Composable
internal fun ReadinessStrip(ready:Int) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
        repeat(10){i->Box(Modifier.weight(1f).height(5.dp).background(if(i<ready) Accent else Outline,RoundedCornerShape(2.dp)))}
    }
}

@Composable
internal fun MatchRoster(blue:List<OnlinePlayer>,red:List<OnlinePlayer>) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text("Синие",color=Blue,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        Text("Красные",color=Red,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
    }
    Role.entries.forEach { role ->
        val b=blue.firstOrNull { it.role==role.name }
        val r=red.firstOrNull { it.role==role.name }
        Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(8.dp)).padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(role.title+(if(role.name=="BARON") " · капитаны" else ""),color=Muted,fontSize=11.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                PlayerCell(b,Blue,Modifier.weight(1f))
                PlayerCell(r,Red,Modifier.weight(1f))
            }
        }
    }
}
@Composable
private fun PlayerCell(player:OnlinePlayer?,color:Color,modifier:Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)){
        Text(player?.riotId ?: "—",fontSize=13.sp,fontWeight=FontWeight.Medium)
        if(player!=null) Text(if(player.ready) "Готов" else "Ожидает",fontSize=11.sp,color=if(player.ready)color else Muted)
    }
}
