package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.model.DashboardTabScope

/** Explicit provider names remain understandable without color vision or a legend. */
@Composable
internal fun DashboardTabScopeBadge(scope: DashboardTabScope) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val (background, foreground) = when (scope) {
        DashboardTabScope.Common -> scheme.primaryContainer to scheme.onPrimaryContainer
        DashboardTabScope.Cursor -> if (dark) Color(0xFF3A3025) to Color(0xFFF2CB95)
            else Color(0xFFF2E6D4) to Color(0xFF73512D)
        DashboardTabScope.Codex -> if (dark) Color(0xFF203C36) to Color(0xFF94D6C4)
            else Color(0xFFE1EFEC) to Color(0xFF2A6358)
    }
    Surface(shape = RoundedCornerShape(5.dp), color = background, contentColor = foreground) {
        Text(stringResource(scope.labelRes), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false)
    }
}
