package com.nendo.argosy.ui.screens.gamedetail.modals

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nendo.argosy.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.MaterialTheme
import com.nendo.argosy.data.remote.romm.LiteBoxProgressValue
import com.nendo.argosy.domain.model.CompletionStatus
import com.nendo.argosy.ui.common.color
import com.nendo.argosy.ui.common.icon
import com.nendo.argosy.ui.components.CenteredModal
import com.nendo.argosy.ui.components.FocusedScroll
import com.nendo.argosy.ui.components.InputButton
import com.nendo.argosy.ui.screens.gamedetail.components.OptionItem
import com.nendo.argosy.ui.common.labelRes

@Composable
fun StatusPickerModal(
    selectedValue: String?,
    currentValue: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    // LiteBox only: the library's own Progress entries. Non-empty, they replace RomM's five statuses
    // and every value handled here is a full "Category / Value" entry rather than an api value.
    liteBoxValues: List<LiteBoxProgressValue> = emptyList()
) {
    val liteBox = liteBoxValues.isNotEmpty()
    val effectiveSelection = selectedValue
        ?: if (liteBox) liteBoxValues.first().value else CompletionStatus.entries.first().apiValue
    val focusedIndex = if (liteBox) {
        liteBoxValues.indexOfFirst { it.value == effectiveSelection }.coerceAtLeast(0)
    } else {
        CompletionStatus.entries.indexOfFirst { it.apiValue == effectiveSelection }.coerceAtLeast(0)
    }

    CenteredModal(
        title = stringResource(R.string.gamedetail_status_picker_title),
        baseWidth = 320.dp,
        onDismiss = onDismiss,
        footerHints = listOf(
            InputButton.DPAD_VERTICAL to
                stringResource(R.string.gamedetail_status_picker_footer_navigate),
            InputButton.A to stringResource(R.string.gamedetail_status_picker_footer_confirm),
            InputButton.B to stringResource(R.string.gamedetail_status_picker_footer_cancel)
        )
    ) {
        val listState = rememberLazyListState()
        FocusedScroll(listState = listState, focusedIndex = focusedIndex)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (liteBox) {
                items(liteBoxValues.size) { index ->
                    val entry = liteBoxValues[index]
                    OptionItem(
                        icon = Icons.Default.Flag,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        label = entry.value,
                        isFocused = entry.value == effectiveSelection,
                        isSelected = entry.value == currentValue,
                        onClick = { onSelect(entry.value) }
                    )
                }
                return@LazyColumn
            }
            items(CompletionStatus.entries.size) { index ->
                val status = CompletionStatus.entries[index]
                val isSelected = status.apiValue == effectiveSelection
                val isCurrent = status.apiValue == currentValue

                OptionItem(
                    icon = status.icon,
                    iconTint = status.color,
                    label = stringResource(status.labelRes),
                    isFocused = isSelected,
                    isSelected = isCurrent,
                    onClick = { onSelect(status.apiValue) }
                )
            }
        }
    }
}
