package dev.lolokeksu.scrimroom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lolokeksu.scrimroom.data.OnlineResult

@Composable
internal fun WinLossStrip(wins:Int,losses:Int){
    val total=wins+losses
    if(total<=0)return
    Row(Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription="$wins побед, $losses поражений" },horizontalArrangement=Arrangement.spacedBy(if(wins>0&&losses>0)2.dp else 0.dp)){
        if(wins>0) Box(Modifier.weight(wins.toFloat()).fillMaxHeight().background(Accent,RoundedCornerShape(3.dp)))
        if(losses>0) Box(Modifier.weight(losses.toFloat()).fillMaxHeight().background(Red,RoundedCornerShape(3.dp)))
    }
}
@Composable
internal fun RatingHistoryChart(results:List<OnlineResult>){
    var limit by rememberSaveable { mutableIntStateOf(20) }
    val rated=results.filter { it.winner!="VOID" && it.beforeRating!=null && it.afterRating!=null }.take(limit).asReversed()
    PanelCard {
        Eyebrow("Динамика Elo")
        ChoiceRow("Последние игры",listOf(10,20,50),limit,{it.toString()}){limit=it}
        if(rated.isEmpty()) {
            Text("Нет завершённых игр с изменением рейтинга",color=Muted,fontSize=13.sp)
        }else{
            val first=rated.first().beforeRating!!
            val last=rated.last().afterRating!!
            val delta=last-first
            val values=listOf(first)+rated.map{it.afterRating!!}
            val low=values.minOrNull()!!;val high=values.maxOrNull()!!
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(last.toString(),fontWeight=FontWeight.Bold,fontSize=26.sp)
                Text((if(delta>0)"+" else "")+delta, color=if(delta>=0)Accent else Red,fontWeight=FontWeight.Bold)
            }
            Text("Диапазон: $low — $high · ${rated.size} игр",fontSize=12.sp,color=Muted)
            Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription="История Elo: начало $first, конец $last, минимум $low, максимум $high" }){
                val padding=5.dp.toPx()
                val width=(size.width-padding*2).coerceAtLeast(1f)
                val height=(size.height-padding*2).coerceAtLeast(1f)
                repeat(3){i->val y=padding+height*i/2;drawLine(Outline,Offset(padding,y),Offset(size.width-padding,y),strokeWidth=1.dp.toPx())}
                val range=(high.toLong()-low.toLong()).coerceAtLeast(1L).toFloat()
                val points=values.mapIndexed { i,v -> Offset(padding+width*i/(values.size-1),if(high==low)padding+height/2 else padding+height*(1-(v.toLong()-low.toLong()).toFloat()/range)) }
                points.zipWithNext().forEach { (a,b)->drawLine(Accent,a,b,strokeWidth=2.dp.toPx()) }
                drawCircle(Accent,3.dp.toPx(),points.last())
            }
            Text("Внутренний Elo клуба, не рейтинг Riot.",color=Muted,fontSize=11.sp)
        }
    }
}
