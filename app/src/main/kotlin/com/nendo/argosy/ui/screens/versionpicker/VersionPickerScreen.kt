package com.nendo.argosy.ui.screens.versionpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nendo.argosy.R
import com.nendo.argosy.ui.components.FooterHints
import com.nendo.argosy.ui.components.InputButton
import com.nendo.argosy.ui.input.LocalInputDispatcher
import com.nendo.argosy.ui.navigation.Screen
import com.nendo.argosy.ui.theme.Dimens
import com.nendo.argosy.ui.util.clickableNoFocus

/**
 * The full-page version/rom picker (Mehdi, 2026-09-06): "faut une grande page pour choisir la
 * version, me met pas une toute petite modale" — a game can offer thousands of choices here, so
 * this is a real navigation destination with its own LazyColumn, not one more of Game Detail's
 * small in-place pickers (disc/variant/memcard). Two screens — versions, then the roms inside one
 * eligible archive — see VersionPickerViewModel for when each shows.
 */
@Composable
fun VersionPickerScreen(
    gameId: Long,
    onBack: () -> Unit,
    onSwitched: (newGameId: Long) -> Unit,
    viewModel: VersionPickerViewModel = hiltViewModel()
) {
    val inputDispatcher = LocalInputDispatcher.current
    val inputHandler = remember(onBack, onSwitched) {
        viewModel.createInputHandler(onBack = onBack, onSwitched = onSwitched)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inputHandler) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inputDispatcher.subscribeView(inputHandler, forRoute = Screen.ROUTE_VERSION_PICKER)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        inputDispatcher.subscribeView(inputHandler, forRoute = Screen.ROUTE_VERSION_PICKER)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(gameId) { viewModel.load(gameId) }

    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.focusIndex, uiState.drilledIntoLabel) {
        if (uiState.rows.isNotEmpty()) listState.animateScrollToItem(uiState.focusIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(Dimens.spacingLg)) {
                Text(
                    text = uiState.drilledIntoLabel ?: uiState.gameTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.drilledIntoLabel != null) {
                    // Which archive these roms come from, then the game — the version label alone
                    // ("Rev A") says nothing about the file being opened.
                    uiState.archiveFileName?.let { archive ->
                        Text(
                            text = stringResource(R.string.version_picker_roms_from, archive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = uiState.gameTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.error?.let { message ->
                    Spacer(modifier = Modifier.height(Dimens.spacingSm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            when {
                uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.rows.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.version_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.spacingLg),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
                ) {
                    itemsIndexed(uiState.rows, key = { index, row -> "$index:${row.appId}:${row.path}" }) { index, row ->
                        VersionRowCard(
                            row = row,
                            isFocused = index == uiState.focusIndex,
                            onClick = { viewModel.selectRow(index, onSwitched = onSwitched) }
                        )
                    }
                }
            }
        }

        if (uiState.isApplying) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        FooterHints(
            hints = buildList {
                add(InputButton.DPAD_VERTICAL to stringResource(R.string.version_picker_footer_navigate))
                add(InputButton.A to stringResource(R.string.version_picker_footer_select))
                if (uiState.drilledIntoLabel != null && uiState.hasVersionListBehind) {
                    add(InputButton.B to stringResource(R.string.version_picker_footer_back_to_versions))
                } else {
                    add(InputButton.B to stringResource(R.string.version_picker_footer_back))
                }
            },
            onHintClick = { button ->
                when (button) {
                    InputButton.A -> inputHandler.onConfirm()
                    InputButton.B -> if (!inputHandler.onBack().handled) onBack()
                    else -> Unit
                }
            }
        )
    }
}

@Composable
private fun VersionRowCard(row: VersionPickerRow, isFocused: Boolean, onClick: () -> Unit) {
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    // clickableNoFocus, like every row in the app: a tap acts, but never steals Compose focus from
    // the index-based highlight the gamepad drives (measured 2026-09-06: without any click handler
    // at all, a tap did nothing).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoFocus(onClick = onClick)
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(Dimens.radiusLg)),
        shape = RoundedCornerShape(Dimens.radiusLg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // What this device is served today — pinned here, or the server's default.
            if (row.isCurrent) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.iconSm)
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                row.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // The desktop picker's own columns, as small marks: last played, favourite, RA match,
            // tag score — then "already downloaded on this device".
            if (row.isLastPlayed) Mark(Icons.Default.History, MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.isFavorite) Mark(Icons.Default.Star, MaterialTheme.colorScheme.primary)
            if (row.hasRa) Mark(Icons.Default.EmojiEvents, MaterialTheme.colorScheme.tertiary)
            if (row.score > 0) {
                Text(
                    text = stringResource(R.string.version_picker_score, row.score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.spacingSm)
                )
            }
            if (row.isLocal) Mark(Icons.Default.DownloadDone, MaterialTheme.colorScheme.primary)
            if (row.isDrillable) {
                row.romCount?.let { count ->
                    Text(
                        text = stringResource(R.string.version_picker_rom_count, count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.spacingSm)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Mark(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.padding(start = Dimens.spacingSm).size(Dimens.iconSm)
    )
}
