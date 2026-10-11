package com.resonix.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.neoInset
import com.resonix.player.ui.neo.neoRaised
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import kotlin.math.roundToInt

/** The three top-level destinations of the app. */
enum class NavTab(val label: String) {
    HOME("Home"),
    TOOLS("Tools"),
    SETTINGS("Settings");

    val icon get() = when (this) {
        HOME -> NeoIcons.Home
        TOOLS -> NeoIcons.Tools
        SETTINGS -> NeoIcons.Settings
    }
}

/**
 * Floating pill that holds the main navigation. The selected tab sits in a pressed-in well that
 * slides between tabs with a little spring.
 */
@Composable
fun FloatingNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = rememberHaptic()
    val tabs = NavTab.entries
    var barWidthPx by remember { mutableIntStateOf(0) }
    val position = animateFloatAsState(
        targetValue = selected.ordinal.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "navPosition",
    )
    val padPx = with(density) { 8.dp.toPx() }
    val cellPx = if (barWidthPx > 0) (barWidthPx - padPx * 2f) / tabs.size else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp)
            .neoRaised(cornerRadius = 37.dp, elevation = 9.dp)
            .onSizeChanged { barWidthPx = it.width },
    ) {
        if (cellPx > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((padPx + cellPx * position.value).roundToInt(), padPx.roundToInt()) }
                    .width(with(density) { cellPx.toDp() })
                    .height(58.dp)
                    .neoInset(cornerRadius = 29.dp, elevation = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            tabs.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == selected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (tab != selected) {
                            haptic()
                            onSelect(tab)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: NavTab,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val neo = LocalNeoColors.current
    val color by animateColorAsState(
        targetValue = if (selected) neo.accent else neo.textTertiary,
        label = "navItemColor",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = tab.icon, contentDescription = tab.label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            color = color,
            style = NeoType.tiny.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}
