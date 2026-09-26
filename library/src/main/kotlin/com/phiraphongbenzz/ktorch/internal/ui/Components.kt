package com.phiraphongbenzz.ktorch.internal.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.phiraphongbenzz.ktorch.R
import com.phiraphongbenzz.ktorch.internal.data.HttpTransaction
import java.util.Locale
import kotlin.math.roundToInt

/** Outer margin of inset-grouped cards, and the corner radius they use. */
internal val CardMargin = 16.dp
private val CardRadius = 12.dp

@Composable
internal fun KIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = palette.label,
    size: Dp = 22.dp,
) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint)
}

/** UIKit-style press feedback: content dims while pressed; no ripple. */
@Composable
private fun rememberPressed(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return interaction to pressed
}

@Composable
internal fun BarButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (interaction, pressed) = rememberPressed()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (!enabled) 0.3f else if (pressed) 0.35f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        KIcon(icon, contentDescription)
    }
}

@Composable
internal fun BackButton(label: String, onClick: () -> Unit) {
    val (interaction, pressed) = rememberPressed()
    Row(
        modifier = Modifier
            .height(44.dp)
            .clickable(interaction, indication = null, onClickLabel = "Back", role = Role.Button, onClick = onClick)
            .alpha(if (pressed) 0.35f else 1f)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KIcon(R.drawable.ktorch_ic_back, contentDescription = null)
        Text(label, style = KType.body, color = palette.label)
    }
}

/**
 * Navigation bar. The inline title and the hairline appear once the large title has scrolled
 * away, as in UIKit.
 */
@Composable
internal fun NavBar(
    title: String,
    titleVisible: Boolean,
    background: Color,
    leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = palette
    val progress by animateFloatAsState(if (titleVisible) 1f else 0f, label = "navTitle")
    Column(Modifier.fillMaxWidth().background(background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .height(44.dp)
                .padding(horizontal = 4.dp),
        ) {
            Box(Modifier.align(Alignment.CenterStart)) { leading() }
            Text(
                text = title,
                style = KType.headline,
                color = p.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 108.dp)
                    .alpha(progress),
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        HorizontalDivider(thickness = Dp.Hairline, color = p.separator.copy(alpha = p.separator.alpha * progress))
    }
}

/** iOS segmented control: gray track, raised thumb that slides to the selection. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
) {
    val p = palette
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(RoundedCornerShape(9.dp))
            .background(p.fill)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val segmentPx = with(LocalDensity.current) { segment.toPx() }
        val position by animateFloatAsState(
            targetValue = selected.toFloat(),
            animationSpec = spring(dampingRatio = 0.86f, stiffness = 700f),
            label = "segment",
        )
        val thumbShape = RoundedCornerShape(7.dp)
        Box(
            Modifier
                // Read in the layout phase only: the slide never recomposes.
                .offset { IntOffset((segmentPx * position).roundToInt(), 0) }
                .width(segment)
                .fillMaxHeight()
                .shadow(if (p.isDark) 0.dp else 2.dp, thumbShape)
                .background(p.segmentThumb, thumbShape),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = index == selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = KType.footnote.copy(
                            fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = p.label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** iOS search field; "Cancel" slides in while it has focus. */
@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = palette
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    BackHandler(enabled = focused) { focusManager.clearFocus() }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.fill)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KIcon(R.drawable.ktorch_ic_search, contentDescription = null, tint = p.secondaryLabel, size = 17.dp)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = KType.body, color = p.secondaryLabel, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = KType.body.copy(color = p.label),
                    cursorBrush = SolidColor(p.label),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable(
                            remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Clear search",
                            role = Role.Button,
                        ) { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    KIcon(R.drawable.ktorch_ic_clear, contentDescription = "Clear", tint = p.secondaryLabel, size = 17.dp)
                }
            }
        }
        AnimatedVisibility(
            visible = focused,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            Text(
                text = "Cancel",
                style = KType.body,
                color = p.label,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
            )
        }
    }
}

/** Capsule badge: status code in its tone, "ERR" for failures, dots while pending. */
@Composable
internal fun StatusPill(t: HttpTransaction, modifier: Modifier = Modifier) {
    val tone = palette.tone(t)
    val text = t.responseCode?.toString() ?: if (t.error != null) "ERR" else "•••"
    Box(
        modifier
            .defaultMinSize(minWidth = 46.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(tone.fill)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = KType.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = KType.TABULAR),
            color = tone.text,
            maxLines = 1,
        )
    }
}

/** Like iOS ContentUnavailableView. */
@Composable
internal fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    val p = palette
    Column(
        modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KIcon(R.drawable.ktorch_ic_launcher_foreground, contentDescription = null, tint = p.tertiaryLabel, size = 96.dp)
        Text(title, style = KType.title3.copy(fontWeight = FontWeight.Bold), color = p.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = KType.subhead, color = p.secondaryLabel, textAlign = TextAlign.Center)
    }
}

/** Uppercase footnote header above an inset-grouped card, with optional trailing controls. */
@Composable
internal fun SectionHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = CardMargin + 16.dp, end = CardMargin, top = 26.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(Locale.ROOT), style = KType.footnote, color = palette.secondaryLabel)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/**
 * One row of an inset-grouped card drawn across several lazy items: the first row rounds the top
 * corners, the last one the bottom.
 */
internal fun Modifier.cardSegment(color: Color, first: Boolean, last: Boolean): Modifier {
    val top = if (first) CardRadius else 0.dp
    val bottom = if (last) CardRadius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    return padding(horizontal = CardMargin).clip(shape).background(color)
}

@Composable
internal fun InfoRow(label: String, value: String, first: Boolean, last: Boolean, isError: Boolean = false) {
    val p = palette
    Column(Modifier.fillMaxWidth().cardSegment(p.card, first, last)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, style = KType.footnote, color = p.secondaryLabel)
            Spacer(Modifier.height(1.dp))
            Text(value, style = KType.subhead, color = if (isError) p.serverError.text else p.label)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = Dp.Hairline, color = p.separator)
    }
}

@Composable
internal fun KMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = palette.elevated,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
    ) {
        content()
    }
}

/** Menu row with the icon trailing, as in iOS menus. */
@Composable
internal fun KMenuItem(
    text: String,
    @DrawableRes icon: Int,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val base = if (destructive) palette.serverError.text else palette.label
    val color = if (enabled) base else base.copy(alpha = 0.3f)
    DropdownMenuItem(
        text = { Text(text, style = KType.body, color = color) },
        trailingIcon = { KIcon(icon, contentDescription = null, tint = color, size = 20.dp) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.widthIn(min = 230.dp),
    )
}

internal fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
