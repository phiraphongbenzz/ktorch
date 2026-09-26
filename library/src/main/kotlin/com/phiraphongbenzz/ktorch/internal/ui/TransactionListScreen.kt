package com.phiraphongbenzz.ktorch.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phiraphongbenzz.ktorch.R
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.format.Formats
import com.phiraphongbenzz.ktorch.internal.format.StatusFilter
import com.phiraphongbenzz.ktorch.internal.share.Sharer
import kotlinx.coroutines.launch

private val FILTERS = listOf(
    StatusFilter.ALL to "All",
    StatusFilter.SUCCESS to "2xx",
    StatusFilter.REDIRECT to "3xx",
    StatusFilter.CLIENT_ERROR to "4xx",
    StatusFilter.SERVER_ERROR to "5xx",
    StatusFilter.FAILED to "Failed",
)

/** Rows start their text here, so separators are inset to it, as in iOS lists. */
private val RowTextInset = 16.dp + 46.dp + 14.dp

@Composable
internal fun TransactionListScreen(
    transactions: List<HttpTransaction>,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: StatusFilter,
    onFilterChange: (StatusFilter) -> Unit,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    allTransactions: List<HttpTransaction>,
) {
    val p = palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // The inline title takes over once the large title/header has slid under the bar.
    val threshold = with(LocalDensity.current) { 40.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(p.background)) {
        NavBar(
            title = "Ktorch",
            titleVisible = collapsed,
            background = p.background,
            trailing = {
                Box {
                    BarButton(R.drawable.ktorch_ic_more, contentDescription = "More") { menuOpen = true }
                    KMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        KMenuItem("Share All", R.drawable.ktorch_ic_share, enabled = allTransactions.isNotEmpty()) {
                            menuOpen = false
                            scope.launch {
                                runCatching { Sharer.shareAll(context, allTransactions) }
                                    .onFailure { toast(context, "Could not share: ${it.message}") }
                            }
                        }
                        KMenuItem("Clear", R.drawable.ktorch_ic_trash, destructive = true, enabled = totalCount > 0) {
                            menuOpen = false
                            onClear()
                        }
                    }
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.union(WindowInsets.ime)
                .only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            item(key = "title") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)) {
                    Text("Ktorch", style = KType.largeTitle, color = p.label)
                    Text(
                        text = when {
                            totalCount == 0 -> "Waiting for requests"
                            transactions.size == totalCount -> "$totalCount requests"
                            else -> "${transactions.size} of $totalCount requests"
                        },
                        style = KType.footnote,
                        color = p.secondaryLabel,
                    )
                }
            }
            item(key = "search") {
                SearchField(query, onQueryChange, placeholder = "Search URL", modifier = Modifier.padding(horizontal = 16.dp))
            }
            item(key = "filter") {
                SegmentedControl(
                    options = FILTERS.map { it.second },
                    selected = FILTERS.indexOfFirst { it.first == filter }.coerceAtLeast(0),
                    onSelect = { onFilterChange(FILTERS[it].first) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            if (transactions.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (totalCount == 0) "No Requests" else "No Results",
                        message = if (totalCount == 0) {
                            "Requests made through Ktor show up here."
                        } else {
                            "No requests match the search or filter."
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
                    )
                }
            } else {
                item(key = "top-rule") {
                    HorizontalDivider(Modifier.padding(start = RowTextInset), thickness = Dp.Hairline, color = p.separator)
                }
                items(transactions, key = { it.id }) { t ->
                    TransactionRow(t, selected = t.id == selectedId, onClick = { onSelect(t.id) })
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(t: HttpTransaction, selected: Boolean, onClick: () -> Unit) {
    val p = palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        pressed -> p.pressed
        selected -> p.fill
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(t)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = p.secondaryLabel, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) {
                            append(t.method)
                        }
                        append("  ")
                        append(t.path)
                    },
                    style = KType.subhead.copy(fontWeight = FontWeight.Medium),
                    color = p.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.scheme == "https") {
                        KIcon(R.drawable.ktorch_ic_lock, contentDescription = "HTTPS", tint = p.secondaryLabel, size = 11.dp)
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(t.host, style = KType.footnote, color = p.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                t.error?.let {
                    Text(it, style = KType.footnote, color = p.serverError.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Formats.time(t.requestTimeMillis),
                    style = KType.footnote.copy(fontFeatureSettings = KType.TABULAR),
                    color = p.secondaryLabel,
                )
                Text(
                    text = "${Formats.duration(t)} · ${Formats.bytes(t.responseBody?.size)}",
                    style = KType.caption.copy(fontFeatureSettings = KType.TABULAR),
                    color = p.secondaryLabel,
                )
            }
            Spacer(Modifier.width(6.dp))
            KIcon(R.drawable.ktorch_ic_chevron, contentDescription = null, tint = p.tertiaryLabel, size = 13.dp)
        }
        HorizontalDivider(Modifier.padding(start = RowTextInset), thickness = Dp.Hairline, color = p.separator)
    }
}

@Composable
internal fun EmptyDetail() {
    Box(Modifier.fillMaxSize().background(palette.groupedBackground), contentAlignment = Alignment.Center) {
        EmptyState(title = "No Selection", message = "Select a request to see its details.")
    }
}
