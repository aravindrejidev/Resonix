package com.resonix.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.resonix.player.data.Track
import com.resonix.player.ui.components.TrackRow
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoSearchField
import com.resonix.player.ui.neo.NeoSectionLabel
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/** Full-screen search over every song the app knows about. */
@Composable
fun SearchScreen(
    tracks: List<Track>,
    currentUri: String,
    isPlaying: Boolean,
    showBadges: Boolean,
    showArt: Boolean,
    onTrackClick: (Track) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, tracks) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            tracks.filter { t ->
                t.title.contains(q, ignoreCase = true) ||
                    t.artist.contains(q, ignoreCase = true) ||
                    t.album.contains(q, ignoreCase = true) ||
                    t.folderName.contains(q, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .neoBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(icon = NeoIcons.Back, onClick = onClose, contentDescription = "Close search")
            Spacer(Modifier.width(14.dp))
            NeoSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = "Songs, artists, albums",
                autoFocus = true,
            )
        }

        when {
            query.isBlank() -> SearchMessage(
                title = "Find anything",
                body = "Search ${tracks.size} songs by title, artist, album or folder name.",
            )
            results.isEmpty() -> SearchMessage(
                title = "No matches",
                body = "Nothing found for “${query.trim()}”.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "count") {
                    NeoSectionLabel(if (results.size == 1) "1 result" else "${results.size} results")
                }
                items(results, key = { it.contentUri }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.contentUri == currentUri,
                        isPlaying = isPlaying,
                        showBadge = showBadges,
                        showArt = showArt,
                        onClick = { onTrackClick(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(title: String, body: String) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeoIconBadge(icon = NeoIcons.Search, diameter = 84.dp)
        Spacer(Modifier.height(24.dp))
        Text(text = title, color = neo.textPrimary, style = NeoType.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text = body, color = neo.textSecondary, style = NeoType.bodyRegular, textAlign = TextAlign.Center)
    }
}
