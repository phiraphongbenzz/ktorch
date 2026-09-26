package com.phiraphongbenzz.ktorch.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phiraphongbenzz.ktorch.internal.Ktorch
import com.phiraphongbenzz.ktorch.internal.data.TransactionStore
import com.phiraphongbenzz.ktorch.internal.format.StatusFilter
import com.phiraphongbenzz.ktorch.internal.format.filterBy

/**
 * List + detail. On expanded widths (tablets, foldables, desktop windows) both panes are shown
 * side by side; Android 16+ ignores orientation and resizability locks there, so the layout
 * has to adapt instead of assuming a phone.
 */
@Composable
internal fun InspectorApp(store: TransactionStore) {
    val all by store.transactions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    val visible = remember(all, query, filter) { all.filterBy(query, filter) }
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }

    val list: @Composable (Boolean) -> Unit = { highlight ->
        TransactionListScreen(
            transactions = visible,
            totalCount = all.size,
            query = query,
            onQueryChange = { query = it },
            filter = filter,
            onFilterChange = { filter = it },
            selectedId = if (highlight) selectedId else null,
            onSelect = { selectedId = it },
            onClear = {
                Ktorch.clear()
                selectedId = null
            },
            allTransactions = all,
        )
    }

    BoxWithConstraints {
        if (maxWidth >= 840.dp) {
            Row {
                Box(Modifier.weight(0.42f)) { list(true) }
                VerticalDivider(thickness = Dp.Hairline, color = palette.separator)
                Box(Modifier.weight(0.58f)) {
                    if (selectedId == null) {
                        EmptyDetail()
                    } else {
                        TransactionDetailScreen(transaction = selected, onBack = null)
                    }
                }
            }
        } else if (selectedId != null) {
            // Registered through OnBackPressedDispatcher, so predictive back animates correctly.
            BackHandler { selectedId = null }
            TransactionDetailScreen(transaction = selected, onBack = { selectedId = null })
        } else {
            list(false)
        }
    }
}
