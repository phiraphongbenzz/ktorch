package com.phiraphongbenzz.ktorch.internal.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phiraphongbenzz.ktorch.R
import com.phiraphongbenzz.ktorch.internal.data.Body
import com.phiraphongbenzz.ktorch.internal.data.Header
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import com.phiraphongbenzz.ktorch.internal.format.CurlFormatter
import com.phiraphongbenzz.ktorch.internal.format.Formats
import com.phiraphongbenzz.ktorch.internal.format.TextExporter
import com.phiraphongbenzz.ktorch.internal.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val TABS = listOf("Overview", "Request", "Response")

/** Items above the tab content: header, stats and tabs. */
private const val TOP_ITEMS = 3

/** Bodies up to this size are formatted inline; larger ones off the main thread. */
private const val SYNC_FORMAT_BYTES = 32 * 1024

/** Documents with up to this many keys are searched inline; larger ones off the main thread. */
private const val SYNC_SEARCH_KEYS = 5_000

/** The scroll-to-top button, and its distance from the screen edges. */
private val TopButtonSize = 44.dp
private val TopButtonMargin = 16.dp

@Composable
internal fun TransactionDetailScreen(transaction: HttpTransaction?, onBack: (() -> Unit)?) {
    val p = palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var tab by rememberSaveable(transaction?.id) { mutableIntStateOf(2) }
    var pretty by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var searching by rememberSaveable(transaction?.id) { mutableStateOf(false) }
    var query by rememberSaveable(transaction?.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    // The inline title takes over once the large title/header has slid under the bar.
    val threshold = with(LocalDensity.current) { 40.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    // Offered once the tabs have scrolled away.
    val showTopButton by remember { derivedStateOf { listState.firstVisibleItemIndex >= TOP_ITEMS } }

    val body = when (tab) {
        1 -> transaction?.requestBody
        2 -> transaction?.responseBody
        else -> null
    }
    val headers = when (tab) {
        1 -> transaction?.requestHeaders
        2 -> transaction?.responseHeaders
        else -> null
    }.orEmpty()
    val note = Formats.bodyNote(body)
    val content = rememberBodyContent(body, pretty, p.json)
    val hits = rememberKeyHits(content, if (searching) query else "")
    var current by remember(hits) { mutableIntStateOf(0) }
    val firstLine = firstLineIndex(headers, note)
    LaunchedEffect(hits) { hits.firstOrNull()?.let { listState.reveal(firstLine + it.line) } }

    fun closeSearch() {
        focusManager.clearFocus()
        searching = false
    }

    fun step(by: Int) {
        if (hits.isEmpty()) return
        current = (current + by).mod(hits.size)
        val index = firstLine + hits[current].line
        scope.launch { listState.reveal(index) }
    }
    BackHandler(enabled = searching) { closeSearch() }

    Column(Modifier.fillMaxSize().background(p.groupedBackground)) {
        NavBar(
            title = transaction?.let { "${it.method} ${it.path}" } ?: "Request",
            titleVisible = collapsed,
            background = p.groupedBackground,
            leading = { if (onBack != null) BackButton("Ktorch", onBack) },
            trailing = {
                if (transaction == null) return@NavBar
                BarButton(R.drawable.ktorch_ic_copy, contentDescription = "Copy request and response") {
                    scope.launch {
                        val text = withContext(Dispatchers.Default) { TextExporter.compact(transaction) }
                        copied(context, Sharer.copy(context, "Request", text))
                    }
                }
                Box {
                    BarButton(R.drawable.ktorch_ic_more, contentDescription = "More") { menuOpen = true }
                    KMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        KMenuItem("Copy as cURL", R.drawable.ktorch_ic_code) {
                            menuOpen = false
                            scope.launch {
                                val curl = withContext(Dispatchers.Default) { CurlFormatter.format(transaction) }
                                copied(context, Sharer.copy(context, "cURL", curl))
                            }
                        }
                        KMenuItem("Share as cURL", R.drawable.ktorch_ic_share) {
                            menuOpen = false
                            scope.launch { share(context) { Sharer.shareCurl(context, transaction) } }
                        }
                        KMenuItem("Copy Body", R.drawable.ktorch_ic_copy) {
                            menuOpen = false
                            val target = if (tab == 1) transaction.requestBody else transaction.responseBody
                            scope.launch {
                                val text = withContext(Dispatchers.Default) { Formats.bodyText(target, pretty) }
                                if (text.isNullOrEmpty()) toast(context, "No body to copy") else copied(context, Sharer.copy(context, "Body", text))
                            }
                        }
                        KMenuItem("Share as Text File", R.drawable.ktorch_ic_doc) {
                            menuOpen = false
                            scope.launch { share(context) { Sharer.shareTransaction(context, transaction) } }
                        }
                    }
                }
            },
            bottom = {
                AnimatedVisibility(
                    visible = searching,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    KeySearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        status = when {
                            !searching || query.isEmpty() -> ""
                            hits.isEmpty() -> "Not found"
                            else -> "${current + 1} of ${hits.size}"
                        },
                        canStep = hits.isNotEmpty(),
                        onPrevious = { step(-1) },
                        onNext = { step(1) },
                        onDone = { closeSearch() },
                    )
                }
            },
        )

        if (transaction == null) {
            EmptyState(
                title = "Request Unavailable",
                message = "It was cleared or dropped from the history.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        Box(Modifier.fillMaxSize()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        // Also lets the last line scroll clear of the keyboard and the scroll-to-top button.
                        bottom = WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
                            .asPaddingValues().calculateBottomPadding() + 28.dp + TopButtonSize,
                    ),
                ) {
                    item(key = "header") { DetailHeader(transaction) }
                    item(key = "stats") { StatsCard(transaction) }
                    item(key = "tabs") {
                        SegmentedControl(
                            options = TABS,
                            selected = tab,
                            onSelect = {
                                tab = it
                                if (it == 0) closeSearch()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = CardMargin, end = CardMargin, top = 20.dp),
                        )
                    }
                    when (tab) {
                        0 -> overview(transaction)
                        else -> message(
                            headers = headers,
                            note = note,
                            content = content,
                            pretty = pretty,
                            onPrettyChange = {
                                pretty = it
                                // Keys are only located in the pretty-printed view.
                                if (!it) closeSearch()
                            },
                            onCopy = { copied(context, Sharer.copy(context, "Body", content.text)) },
                            onSearch = {
                                pretty = true
                                searching = true
                            },
                            hits = hits,
                            current = current,
                        )
                    }
                }
            }
            ScrollToTopButton(
                visible = showTopButton,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                    .padding(TopButtonMargin),
            )
        }
    }
}

/** Small bodies format inline (no flash of empty content); large ones off the main thread. */
@Composable
private fun rememberBodyContent(body: Body?, pretty: Boolean, colors: JsonColors): BodyContent {
    if ((body?.bytes?.size ?: 0) <= SYNC_FORMAT_BYTES) {
        return remember(body, pretty, colors) { BodyContent.build(body, pretty, colors) }
    }
    val content by produceState(BodyContent.EMPTY, body, pretty, colors) {
        value = BodyContent.EMPTY
        value = withContext(Dispatchers.Default) { BodyContent.build(body, pretty, colors) }
    }
    return content
}

/** Keys matching [query]; big documents are searched off the main thread, keeping the last hits meanwhile. */
@Composable
private fun rememberKeyHits(content: BodyContent, query: String): List<LineSpan> {
    if (content.keys.size <= SYNC_SEARCH_KEYS) return remember(content, query) { content.findKeys(query) }
    val hits by produceState(emptyList<LineSpan>(), content, query) {
        value = withContext(Dispatchers.Default) { content.findKeys(query) }
    }
    return hits
}

/** Scrolls item [index] into view with two rows of context above, unless it is fully visible. */
private suspend fun LazyListState.reveal(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    // Rows behind the bottom padding (keyboard, scroll-to-top button) are not really visible.
    val end = info.viewportEndOffset - info.afterContentPadding
    if (item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= end) return
    animateScrollToItem(maxOf(index - 2, 0))
}

@Composable
private fun DetailHeader(t: HttpTransaction) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(t)
            Spacer(Modifier.width(8.dp))
            Text(
                text = t.method,
                style = KType.footnote.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
                color = p.secondaryLabel,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Formats.time(t.requestTimeMillis),
                style = KType.footnote.copy(fontFeatureSettings = KType.TABULAR),
                color = p.secondaryLabel,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(t.path, style = KType.title3, color = p.label)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.scheme == "https") {
                KIcon(R.drawable.ktorch_ic_lock, contentDescription = "HTTPS", tint = p.secondaryLabel, size = 12.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text(t.host, style = KType.subhead, color = p.secondaryLabel)
        }
        t.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = KType.footnote, color = p.serverError.text)
        }
    }
}

@Composable
private fun StatsCard(t: HttpTransaction) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardMargin)
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
            .height(IntrinsicSize.Min)
            .padding(vertical = 12.dp),
    ) {
        Stat("Duration", Formats.duration(t), Modifier.weight(1f))
        VerticalDivider(thickness = Dp.Hairline, color = p.separator)
        Stat("Response", Formats.bytes(t.responseBody?.size), Modifier.weight(1f))
        VerticalDivider(thickness = Dp.Hairline, color = p.separator)
        Stat("Request", Formats.bytes(t.requestBody.size), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val p = palette
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label.uppercase(Locale.ROOT),
            style = KType.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
            color = p.secondaryLabel,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = KType.headline.copy(fontFeatureSettings = KType.TABULAR), color = p.label)
    }
}

private fun LazyListScope.overview(t: HttpTransaction) {
    val request = buildList {
        add("URL" to t.url)
        add("Method" to t.method)
        t.protocol?.let { add("Protocol" to it) }
        add("Started" to Formats.dateTime(t.requestTimeMillis))
        t.requestBody.contentType?.let { add("Content type" to it) }
        add("Body size" to Formats.bytes(t.requestBody.size))
    }
    val response = buildList {
        add("Status" to Formats.status(t))
        t.error?.let { add("Error" to it) }
        add("Duration" to Formats.duration(t))
        add("Time to headers" to Formats.duration(t.headersMillis))
        t.responseBody?.contentType?.let { add("Content type" to it) }
        add("Body size" to Formats.bytes(t.responseBody?.size))
    }
    item(key = "request-header") { SectionHeader("Request") }
    itemsIndexed(request, key = { i, _ -> "request-$i" }) { i, (label, value) ->
        InfoRow(label, value, first = i == 0, last = i == request.lastIndex)
    }
    item(key = "response-header") { SectionHeader("Response") }
    itemsIndexed(response, key = { i, _ -> "response-$i" }) { i, (label, value) ->
        InfoRow(label, value, first = i == 0, last = i == response.lastIndex, isError = label == "Error")
    }
}

/** List index of body line 0: [TOP_ITEMS], then the rows [message] adds before the lines. */
private fun firstLineIndex(headers: List<Header>, note: String?): Int =
    TOP_ITEMS + 1 + maxOf(headers.size, 1) + 1 + (if (note != null) 1 else 0)

private fun LazyListScope.message(
    headers: List<Header>,
    note: String?,
    content: BodyContent,
    pretty: Boolean,
    onPrettyChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    hits: List<LineSpan>,
    current: Int,
) {
    item(key = "headers-header") { SectionHeader(if (headers.isEmpty()) "Headers" else "Headers · ${headers.size}") }
    if (headers.isEmpty()) {
        item(key = "headers-none") { NoteRow("No headers", first = true, last = true) }
    } else {
        itemsIndexed(headers, key = { i, _ -> "header-$i" }) { i, header ->
            InfoRow(header.name, header.value, first = i == 0, last = i == headers.lastIndex)
        }
    }
    item(key = "body-header") {
        SectionHeader("Body") {
            if (content.isJson) HeaderButton(R.drawable.ktorch_ic_search, label = null, contentDescription = "Search keys", onClick = onSearch)
            // Copy sits next to the Pretty/Raw switch and copies exactly what is shown.
            if (content.text.isNotEmpty()) HeaderButton(R.drawable.ktorch_ic_copy, label = "Copy", contentDescription = null, onClick = onCopy)
            if (content.isJson) {
                Spacer(Modifier.width(8.dp))
                SegmentedControl(
                    options = listOf("Pretty", "Raw"),
                    selected = if (pretty) 0 else 1,
                    onSelect = { onPrettyChange(it == 0) },
                    modifier = Modifier.width(124.dp),
                    height = 28.dp,
                )
            }
        }
    }
    val lines = content.lines
    if (note != null) {
        item(key = "body-note") { NoteRow(note, first = true, last = lines.isEmpty()) }
    }
    itemsIndexed(lines, key = { i, _ -> "line-$i" }) { i, line ->
        // Pretty JSON has at most one key per line, so at most one hit.
        val hit = hits.binarySearchBy(i) { it.line }
        BodyLine(
            line = line,
            highlighted = content.highlighted,
            first = note == null && i == 0,
            last = i == lines.lastIndex,
            hit = hits.getOrNull(hit),
            current = hit == current,
        )
    }
}

@Composable
private fun BodyLine(line: AnnotatedString, highlighted: Boolean, first: Boolean, last: Boolean, hit: LineSpan?, current: Boolean) {
    val p = palette
    val text = if (hit == null) {
        line
    } else {
        buildAnnotatedString {
            append(line)
            // The current hit reads as in iOS find in page: black on yellow.
            val style = if (current) SpanStyle(color = Color.Black, background = p.currentMatch) else SpanStyle(background = p.match)
            addStyle(style, hit.start, hit.end)
        }
    }
    Text(
        text = text,
        style = KType.mono,
        // With syntax colors on, uncolored text is punctuation: mute it so values stand out.
        color = if (highlighted) p.secondaryLabel else p.label,
        modifier = Modifier
            .fillMaxWidth()
            .cardSegment(p.card, first, last)
            .padding(horizontal = 14.dp)
            .padding(top = if (first) 12.dp else 0.dp, bottom = if (last) 12.dp else 0.dp),
    )
}

@Composable
private fun NoteRow(text: String, first: Boolean, last: Boolean) {
    val p = palette
    Column(Modifier.fillMaxWidth().cardSegment(p.card, first, last)) {
        Text(
            text = text,
            style = KType.footnote,
            color = p.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
        )
        if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = Dp.Hairline, color = p.separator)
    }
}

/** Small button for a section header, e.g. Copy; icon only when [label] is null. */
@Composable
private fun HeaderButton(@DrawableRes icon: Int, label: String?, contentDescription: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .alpha(if (pressed) 0.35f else 1f)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KIcon(icon, contentDescription = contentDescription, size = 16.dp)
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(label, style = KType.footnote.copy(fontWeight = FontWeight.SemiBold), color = palette.label)
        }
    }
}

/** Find bar for JSON keys, under the nav bar: field with the hit count, previous/next and Done. */
@Composable
private fun KeySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    status: String,
    canStep: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val p = palette
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBox(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search keys",
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.focusRequester(focus),
            // Hits update while typing; the search key only hides the keyboard to show more lines.
            onSearch = { focusManager.clearFocus() },
        ) {
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = KType.footnote.copy(fontFeatureSettings = KType.TABULAR),
                    color = p.secondaryLabel,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        BarButton(R.drawable.ktorch_ic_chevron_up, contentDescription = "Previous match", enabled = canStep, onClick = onPrevious)
        BarButton(R.drawable.ktorch_ic_chevron_down, contentDescription = "Next match", enabled = canStep, onClick = onNext)
        Text(
            text = "Done",
            style = KType.body.copy(fontWeight = FontWeight.SemiBold),
            color = p.label,
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDone)
                .padding(horizontal = 4.dp, vertical = 10.dp),
        )
    }
}

/** Floating button that brings a long body back to the top. */
@Composable
private fun ScrollToTopButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = palette
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .size(TopButtonSize)
                .shadow(if (p.isDark) 0.dp else 6.dp, CircleShape)
                .background(p.elevated, CircleShape)
                // No shadow shows on black, so a hairline marks the edge instead.
                .then(if (p.isDark) Modifier.border(Dp.Hairline, p.separator, CircleShape) else Modifier)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                .alpha(if (pressed) 0.35f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            KIcon(R.drawable.ktorch_ic_arrow_up, contentDescription = "Scroll to top", size = 20.dp)
        }
    }
}

private suspend fun share(context: Context, action: suspend () -> Unit) {
    runCatching { action() }.onFailure { toast(context, "Could not share: ${it.message}") }
}

private fun copied(context: Context, ok: Boolean) {
    when {
        !ok -> toast(context, "Too large for the clipboard. Use Share instead.")
        // Android 13+ shows its own clipboard confirmation.
        Build.VERSION.SDK_INT < 33 -> toast(context, "Copied")
    }
}
