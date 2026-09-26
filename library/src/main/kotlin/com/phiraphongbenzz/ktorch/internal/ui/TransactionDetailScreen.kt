package com.phiraphongbenzz.ktorch.internal.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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
import com.phiraphongbenzz.ktorch.internal.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val TABS = listOf("Overview", "Request", "Response")

/** Bodies up to this size are formatted inline; larger ones off the main thread. */
private const val SYNC_FORMAT_BYTES = 32 * 1024

@Composable
internal fun TransactionDetailScreen(transaction: HttpTransaction?, onBack: (() -> Unit)?) {
    val p = palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(transaction?.id) { mutableIntStateOf(2) }
    var pretty by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The inline title takes over once the large title/header has slid under the bar.
    val threshold = with(LocalDensity.current) { 40.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }

    val body = when (tab) {
        1 -> transaction?.requestBody
        2 -> transaction?.responseBody
        else -> null
    }
    val content = rememberBodyContent(body, pretty, p.json)

    Column(Modifier.fillMaxSize().background(p.groupedBackground)) {
        NavBar(
            title = transaction?.let { "${it.method} ${it.path}" } ?: "Request",
            titleVisible = collapsed,
            background = p.groupedBackground,
            leading = { if (onBack != null) BackButton("Ktorch", onBack) },
            trailing = {
                if (transaction == null) return@NavBar
                BarButton(R.drawable.ktorch_ic_share, contentDescription = "Share as text file") {
                    scope.launch { share(context) { Sharer.shareTransaction(context, transaction) } }
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
        )

        if (transaction == null) {
            EmptyState(
                title = "Request Unavailable",
                message = "It was cleared or dropped from the history.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                        .asPaddingValues().calculateBottomPadding() + 28.dp,
                ),
            ) {
                item(key = "header") { DetailHeader(transaction) }
                item(key = "stats") { StatsCard(transaction) }
                item(key = "tabs") {
                    SegmentedControl(
                        options = TABS,
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = CardMargin, end = CardMargin, top = 20.dp),
                    )
                }
                when (tab) {
                    0 -> overview(transaction)
                    else -> message(
                        headers = if (tab == 1) transaction.requestHeaders else transaction.responseHeaders,
                        body = body,
                        content = content,
                        pretty = pretty,
                        onPrettyChange = { pretty = it },
                        onCopy = { copied(context, Sharer.copy(context, "Body", content.text)) },
                    )
                }
            }
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

private fun LazyListScope.message(
    headers: List<Header>,
    body: Body?,
    content: BodyContent,
    pretty: Boolean,
    onPrettyChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
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
            // Copy sits next to the Pretty/Raw switch and copies exactly what is shown.
            if (content.text.isNotEmpty()) CopyButton(onCopy)
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
    val note = Formats.bodyNote(body)
    val lines = content.lines
    if (note != null) {
        item(key = "body-note") { NoteRow(note, first = true, last = lines.isEmpty()) }
    }
    itemsIndexed(lines, key = { i, _ -> "line-$i" }) { i, line ->
        BodyLine(line, content.highlighted, first = note == null && i == 0, last = i == lines.lastIndex)
    }
}

@Composable
private fun BodyLine(line: AnnotatedString, highlighted: Boolean, first: Boolean, last: Boolean) {
    val p = palette
    Text(
        text = line,
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

@Composable
private fun CopyButton(onClick: () -> Unit) {
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
        KIcon(R.drawable.ktorch_ic_copy, contentDescription = null, size = 16.dp)
        Spacer(Modifier.width(4.dp))
        Text("Copy", style = KType.footnote.copy(fontWeight = FontWeight.SemiBold), color = palette.label)
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
