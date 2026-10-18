package com.lamuier.agenT.ui.theme

import androidx.compose.animation.core.spring

/** Stable Compose springs aligned with Material 3 Expressive motion specifications. */
internal object AgenTMotion {
    fun <T> selection() = spring<T>(dampingRatio = 0.85f, stiffness = 500f)
    fun <T> content() = spring<T>(dampingRatio = 0.9f, stiffness = 380f)
    /** Spatial bounds and sheet transitions aligned with Material 3 Expressive motion. */
    fun <T> spatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    /** Tactile touch feedback and subtle scale press response. */
    fun <T> expressivePress() = spring<T>(dampingRatio = 0.65f, stiffness = 800f)
}
