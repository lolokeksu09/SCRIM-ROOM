package dev.lolokeksu.scrimroom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lolokeksu.scrimroom.ui.HistoryScreen
import dev.lolokeksu.scrimroom.ui.HomeScreen
import dev.lolokeksu.scrimroom.ui.ProfileScreen
import dev.lolokeksu.scrimroom.ui.QueueScreen
import dev.lolokeksu.scrimroom.ui.ScrimTheme
import dev.lolokeksu.scrimroom.ui.ScrimViewModel

private enum class Tab(val title: String) { HOME("Главная"), PROFILE("Профиль"), QUEUE("Очередь"), HISTORY("Матчи") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScrimTheme { ScrimApp() } }
    }
}

@Composable
private fun ScrimApp(vm: ScrimViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    val tab = Tab.entries.firstOrNull { it.name == tabName } ?: Tab.HOME
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.HOME -> Icons.Filled.Home
                        Tab.PROFILE -> Icons.Filled.Person
                        Tab.QUEUE -> Icons.Filled.People
                        Tab.HISTORY -> Icons.Filled.History
                    }
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tabName = item.name },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        label = { Text(item.title, fontSize = 11.sp) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0A0C0E),
                            selectedTextColor = Color(0xFFCCE879),
                            indicatorColor = Color(0xFFCCE879),
                            unselectedIconColor = Color(0xFF8F989B),
                            unselectedTextColor = Color(0xFF8F989B)
                        )
                    )
                }
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            AppHeader()
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        state = state,
                        onProfile = { tabName = Tab.PROFILE.name },
                        onQueue = { tabName = Tab.QUEUE.name }
                    )
                    Tab.PROFILE -> ProfileScreen(state.profile, state, vm)
                    Tab.QUEUE -> QueueScreen(state, vm, onProfile = { tabName = Tab.PROFILE.name })
                    Tab.HISTORY -> HistoryScreen(state.demoHistory, state, vm)
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(Color(0xFFCCE879), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("SR", color = Color(0xFF0B0D0D), fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("SCRIM ROOM", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
            Text("CUSTOM MATCH OPERATIONS", fontSize = 9.sp, color = Color(0xFF90999B), letterSpacing = 1.0.sp)
        }
        Text(
            "PILOT ${BuildConfig.VERSION_NAME}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFFCCE879),
            modifier = Modifier.background(Color(0xFF20291C), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
