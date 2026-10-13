package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Window-relative pixel bounds from Jetpack, never a manufacturer property. */
internal val LocalWindowFolds = staticCompositionLocalOf<List<Rect>> { emptyList() }
internal enum class PaneArrangement { Single, Columns, Rows }
internal val LocalPaneArrangement = staticCompositionLocalOf { PaneArrangement.Single }

@Composable
internal fun FoldAwareWindow(content: @Composable () -> Unit) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val folds = posture.hingeList.filter { it.isSeparating || it.isOccluding }.map { it.bounds }
    CompositionLocalProvider(LocalWindowFolds provides folds, content = content)
}

internal data class PaneLayout(val first: Rect, val second: Rect? = null) {
    val paired get() = second != null
}

/** Zero-thickness creases are valid separators, as well as opaque hinges. */
private fun splitAt(region: Rect, fold: Rect, clearance: Float): List<Rect>? {
    val vertical = fold.height > fold.width
    if (vertical) {
        if (fold.right <= region.left || fold.left >= region.right ||
            fold.bottom <= region.top || fold.top >= region.bottom) return null
        return listOf(Rect(region.left, region.top, (fold.left - clearance).coerceIn(region.left, region.right), region.bottom),
            Rect((fold.right + clearance).coerceIn(region.left, region.right), region.top, region.right, region.bottom))
    }
    if (fold.bottom <= region.top || fold.top >= region.bottom ||
        fold.right <= region.left || fold.left >= region.right) return null
    return listOf(Rect(region.left, region.top, region.right, (fold.top - clearance).coerceIn(region.top, region.bottom)),
        Rect(region.left, (fold.bottom + clearance).coerceIn(region.top, region.bottom), region.right, region.bottom))
}

internal fun safePane(window: Rect, folds: List<Rect>, clearance: Float = 0f): Rect =
    folds.fold(window) { region, fold -> splitAt(region, fold, clearance)?.maxBy { it.width * it.height } ?: region }

internal fun paneLayout(window: Rect, folds: List<Rect>, minWidth: Float, minHeight: Float,
    gap: Float, allowPair: Boolean = true, primaryFraction: Float = 0.5f,
    preferredPrimaryWidth: Float? = null): PaneLayout {
    val separators = folds.mapNotNull { splitAt(window, it, gap / 2) }
    if (allowPair && separators.size == 1) {
        val parts = separators.single()
        if (parts.all { it.width >= minWidth && it.height >= minHeight }) return PaneLayout(parts[0], parts[1])
    }
    if (separators.isNotEmpty()) return PaneLayout(safePane(window, folds, gap / 2))
    if (allowPair && window.width >= minWidth * 2 + gap && window.height >= minHeight) {
        val usable = window.width - gap
        val firstWidth = (preferredPrimaryWidth ?: (usable * primaryFraction.coerceIn(0.3f, 0.7f)))
            .coerceIn(minWidth, usable - minWidth)
        return PaneLayout(Rect(window.left, window.top, window.left + firstWidth, window.bottom),
            Rect(window.left + firstWidth + gap, window.top, window.right, window.bottom))
    }
    return PaneLayout(window)
}

/** Account for the actual origin after navigation, app bars, system and keyboard insets. */
@Composable
internal fun AdaptivePaneLayout(
    modifier: Modifier = Modifier,
    allowPair: Boolean = true,
    minPaneWidthDp: Float = 320f,
    maxContentWidthDp: Float = 1200f,
    primaryFraction: Float = 0.5f,
    preferredPrimaryWidthDp: Float? = null,
    single: @Composable () -> Unit,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    val folds = LocalWindowFolds.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val plan = with(density) {
            val width = maxWidth.toPx()
            val height = maxHeight.toPx()
            val localFolds = folds.map { it.translate(-origin) }
            val inset = if (localFolds.isEmpty()) ((width - maxContentWidthDp.dp.toPx()) / 2).coerceAtLeast(0f) else 0f
            paneLayout(Rect(inset, 0f, width - inset, height), localFolds,
                (minPaneWidthDp * fontScale.coerceAtLeast(1f)).dp.toPx(),
                (160f * fontScale.coerceAtLeast(1f)).dp.toPx(), 16.dp.toPx(), allowPair,
                primaryFraction, preferredPrimaryWidthDp?.dp?.toPx())
        }
        @Composable fun Pane(rect: Rect, content: @Composable () -> Unit) {
            Box(with(density) { Modifier.absoluteOffset(rect.left.toDp(), rect.top.toDp())
                .size(rect.width.toDp(), rect.height.toDp()) }) { content() }
        }
        val arrangement = when {
            !plan.paired -> PaneArrangement.Single
            requireNotNull(plan.second).top > plan.first.top -> PaneArrangement.Rows
            else -> PaneArrangement.Columns
        }
        CompositionLocalProvider(LocalPaneArrangement provides arrangement) {
        if (plan.paired) {
            Pane(plan.first, primary)
            Pane(requireNotNull(plan.second), secondary)
        } else Pane(plan.first, single)
        }
    }
}

@Composable
internal fun HingeSafeLayout(content: @Composable () -> Unit) {
    AdaptivePaneLayout(allowPair = false, single = content, primary = content, secondary = {})
}

/** Move the same compositions between scroll containers without losing their state. */
@Composable
internal fun AdaptiveServiceContent(
    primary: @Composable ColumnScope.(Boolean) -> Unit,
    secondary: @Composable ColumnScope.(Boolean) -> Unit,
    allowPair: Boolean = true,
    secondaryFirst: Boolean = false,
    primaryFraction: Float = 0.5f,
    maxContentWidthDp: Float = 1200f,
) {
    val currentPrimary by rememberUpdatedState(primary)
    val currentSecondary by rememberUpdatedState(secondary)
    val first = remember { movableContentOf<ColumnScope, Boolean> { scope, compact -> currentPrimary(scope, compact) } }
    val second = remember { movableContentOf<ColumnScope, Boolean> { scope, compact -> currentSecondary(scope, compact) } }
    val firstScroll = androidx.compose.foundation.rememberScrollState()
    val secondScroll = androidx.compose.foundation.rememberScrollState()
    AdaptivePaneLayout(allowPair = allowPair, primaryFraction = primaryFraction, maxContentWidthDp = maxContentWidthDp,
        single = { AdaptiveTabContent(scrollState = firstScroll) { compact ->
            if (secondaryFirst) { second(this, compact); first(this, compact) }
            else { first(this, compact); second(this, compact) }
        } },
        primary = { AdaptiveTabContent(scrollState = firstScroll) { first(this, it) } },
        secondary = { AdaptiveTabContent(scrollState = secondScroll) { second(this, it) } })
}

@Composable
internal fun AdaptiveLazyServiceContent(
    primary: androidx.compose.foundation.lazy.LazyListScope.(Boolean) -> Unit,
    secondary: androidx.compose.foundation.lazy.LazyListScope.(Boolean) -> Unit,
) {
    val firstState = androidx.compose.foundation.lazy.rememberLazyListState()
    val secondState = androidx.compose.foundation.lazy.rememberLazyListState()
    AdaptivePaneLayout(primaryFraction = 0.38f, maxContentWidthDp = 1440f,
        single = { AdaptiveLazyTabContent(state = firstState) { compact -> primary(compact); secondary(compact) } },
        primary = { AdaptiveLazyTabContent(state = firstState, content = primary) },
        secondary = { AdaptiveLazyTabContent(state = secondState, content = secondary) })
}

/** Keep titles and actions clear of a separator as well as the page body. */
@Composable
internal fun FoldSafeAppBar(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .height(64.dp)) { HingeSafeLayout(content) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveFilterRow(spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable () -> Unit) {
    if (LocalPaneArrangement.current == PaneArrangement.Columns) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    } else Row(Modifier.fillMaxWidth().then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())),
        horizontalArrangement = Arrangement.spacedBy(spacing)) { content() }
}
