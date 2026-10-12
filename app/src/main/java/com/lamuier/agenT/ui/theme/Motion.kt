package com.lamuier.agenT.ui.theme

import androidx.compose.animation.core.spring

/** Stable Compose springs, shared by selection and card transitions. */
internal object AgenTMotion {
    fun <T> selection() = spring<T>(dampingRatio = 0.85f, stiffness = 500f)
    fun <T> content() = spring<T>(dampingRatio = 0.9f, stiffness = 380f)
}
