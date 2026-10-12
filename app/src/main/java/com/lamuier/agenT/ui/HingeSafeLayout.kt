package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity

/** Keep a single-pane screen in the larger unobstructed region of a separating fold. */
internal fun safePane(window: Rect, folds: List<Rect>): Rect = folds.fold(window) { region, fold ->
    if (!region.overlaps(fold)) region else {
        val candidates = listOf(
            Rect(region.left, region.top, fold.left.coerceIn(region.left, region.right), region.bottom),
            Rect(fold.right.coerceIn(region.left, region.right), region.top, region.right, region.bottom),
            Rect(region.left, region.top, region.right, fold.top.coerceIn(region.top, region.bottom)),
            Rect(region.left, fold.bottom.coerceIn(region.top, region.bottom), region.right, region.bottom),
        )
        candidates.maxBy { it.width * it.height }
    }
}

@Composable
internal fun HingeSafeLayout(content: @Composable () -> Unit) {
    val info = currentWindowAdaptiveInfoV2()
    val folds = info.windowPosture.hingeList.filter { it.isOccluding || it.isSeparating }.map { it.bounds }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val window = with(density) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
        val pane = safePane(window, folds)
        Box(with(density) { Modifier.fillMaxSize().absolutePadding(left = pane.left.toDp(), top = pane.top.toDp(),
            right = (window.right - pane.right).toDp(), bottom = (window.bottom - pane.bottom).toDp()) }) { content() }
    }
}
