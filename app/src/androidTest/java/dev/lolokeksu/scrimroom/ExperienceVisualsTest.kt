package dev.lolokeksu.scrimroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lolokeksu.scrimroom.data.ClubFixture
import dev.lolokeksu.scrimroom.data.OnlineResult
import dev.lolokeksu.scrimroom.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceVisualsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun actualRatingValuesAndUnassignedFinal(){
        val results=listOf(
            OnlineResult("3","VOID","BLUE",3,"MID"),
            OnlineResult("2","RED","BLUE",2,"MID",1612,1600),
            OnlineResult("1","BLUE","BLUE",1,"MID",1600,1612)
        )
        val semis=listOf(
            ClubFixture("1","a","b","Alpha","Beta","SEMIFINAL_1","FINISHED",100,"a","cup"),
            ClubFixture("2","c","d","Gamma","Delta","SEMIFINAL_2","SCHEDULED",100,null,"cup")
        )
        compose.setContent{ScrimTheme{Column(Modifier.verticalScroll(rememberScrollState())){
            RatingHistoryChart(results)
            CupBracket(semis,"RUNNING")
        }}}
        compose.onNodeWithContentDescription("История Elo: начало 1600, конец 1600, минимум 1600, максимум 1612").assertExists()
        compose.onNodeWithText("Финал ещё не назначен").assertExists()
        compose.onNodeWithText("Победитель полуфинала 2").assertExists()
    }
}
