package com.wanderwildwood.aikotoba.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.delay

/** An icon in a top bar, with a target a thumb can find. */
@Composable
internal fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A word in the top bar that does something — Save — bold when it is ready to. */
@Composable
internal fun BarWord(text: String, ready: Boolean = true, onClick: () -> Unit) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (ready) FontWeight.Bold else null,
        modifier = Modifier.clickable(enabled = ready, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** A top bar's title, in bold as the phone's own apps set it. */
@Composable
internal fun BarTitle(text: String) {
    // Black, not Bold: the phone's own titles measure a 5px stem at this size, Bold a 4px one.
    TextMMD(text = text, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun Bar(
    title: String,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.Back,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        TopAppBarMMD(
            title = { BarTitle(title) },
            navigationIcon = {
                if (onBack != null) {
                    val says = if (backIcon == Icons.Close) com.wanderwildwood.aikotoba.R.string.cd_close else com.wanderwildwood.aikotoba.R.string.cd_back
                    BarButton(backIcon, androidx.compose.ui.res.stringResource(says), onBack)
                }
            },
            actions = actions,
            showDivider = false,
        )
        HorizontalDividerMMD(thickness = 3.dp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A row that asks before it acts: the first press arms it and changes what it says, a second
 * press does it, and it disarms itself after four seconds so nothing is left live for whoever
 * picks the phone up next. Returns whether it is armed, and the press to hand the row.
 */
@Composable
internal fun rememberArmed(key: Any?, onConfirmed: () -> Unit): Pair<Boolean, () -> Unit> {
    var armed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    return armed to {
        if (armed) {
            armed = false
            onConfirmed()
        } else {
            armed = true
        }
    }
}

/** A small heading over a group of rows. */
@Composable
internal fun Heading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
    HorizontalDividerMMD()
}

/** A row: what it is, and a line under it only when that line says something. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlainRow(
    title: String,
    note: String? = null,
    bold: Boolean = false,
    noteLines: Int = 2,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onPress: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onPress != null || onLongPress != null) it.combinedClickable(onClick = onPress ?: {}, onLongClick = onLongPress) else it }
            .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (bold) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!note.isNullOrEmpty()) {
                TextMMD(text = note, style = MaterialTheme.typography.labelSmall, maxLines = noteLines, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
    DottedRule()
}

/** A button along the foot of a screen or in a dialog. */
@Composable
internal fun FootButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButtonMMD(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A short thing to say, along the foot, gone after a few seconds. */
@Composable
internal fun NoticeStrip(text: String, onSeen: () -> Unit) {
    LaunchedEffect(text) {
        delay(5000)
        onSeen()
    }
    HorizontalDividerMMD()
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSeen).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The rule between rows: dotted, as the phone's own lists draw it. */
@Composable
internal fun DottedRule(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp),
    ) {
        drawLine(
            color = ink,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx().coerceAtMost(1.5f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.3.dp.toPx(), 1.5.dp.toPx())),
        )
    }
}

/** A sentence on its own, in the body of a screen. */
@Composable
internal fun Say(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else null,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
