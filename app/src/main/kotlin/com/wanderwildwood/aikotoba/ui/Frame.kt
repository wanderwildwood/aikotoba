package com.wanderwildwood.aikotoba.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One line to say along the foot of whatever screen is up: saved, copied, not saved and why. */
object Notice {
    var text by mutableStateOf<String?>(null)
    fun say(message: String) {
        text = message
    }
}

/** Every screen's shape: the bar, the body, and the notice strip along the foot. */
@Composable
internal fun Frame(
    title: String,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.Back,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        // The notice along the foot goes with the next press, before that press does its own
        // thing, so a press that says something new leaves the new thing said.
        modifier = Modifier.onEachPress { Notice.text = null },
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Bar(title, onBack, backIcon, actions) },
        bottomBar = { Notice.text?.let { t -> Column { NoticeStrip(t) { if (Notice.text == t) Notice.text = null } } } },
        // 3 dp above the Scaffold's own 16, so the corner button sits at the height of
        // Messaging's pencil, which rests on a 3 dp rule (23 Nov 2026).
        floatingActionButton = { Box(Modifier.padding(bottom = 3.dp)) { floating() } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), content = content)
    }
}
