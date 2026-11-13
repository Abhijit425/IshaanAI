package com.ishaan.ai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.ChildProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Holds the loaded profile; null while loading, NoProfile when none exists. */
sealed class ProfileState { data object Loading : ProfileState(); data object None : ProfileState(); data class Ready(val p: ChildProfile) : ProfileState() }

@Composable
fun profileState(): ProfileState {
    val c = LocalContainer.current
    val flow = remember { c.db.profileDao().observeProfile().map { if (it == null) ProfileState.None else ProfileState.Ready(it) } }
    return flow.collectAsStateWithLifecycle(initialValue = ProfileState.Loading).value
}

/** Runs [content] with the child's profile, or asks the parent to create one. */
@Composable
fun WithProfile(nav: NavHostController, content: @Composable (ChildProfile) -> Unit) {
    when (val s = profileState()) {
        is ProfileState.Ready -> content(s.p)
        ProfileState.Loading -> Box(Modifier.fillMaxSize()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        ProfileState.None -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Let's start with Ishaan's profile", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Enter his name, date of birth and diagnosis. Nothing is filled in for you — you enter and verify everything.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.navigate("profile") }, modifier = Modifier.fillMaxWidth()) { Text("Create profile") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigate("settings") }, modifier = Modifier.fillMaxWidth()) { Text("Restore a backup or try sample data") }
        }
    }
}

@Composable
fun <T> Flow<T>.collectOr(initial: T): State<T> = collectAsStateWithLifecycle(initialValue = initial)

@Composable
fun <T> rememberFlow(key: Any?, initial: T, block: () -> Flow<T>): T {
    val f = remember(key) { runCatching(block).getOrElse { flowOf(initial) } }
    return f.collectAsStateWithLifecycle(initialValue = initial).value
}
