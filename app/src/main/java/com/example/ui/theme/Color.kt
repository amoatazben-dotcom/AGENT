package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand Cyber / Neural Palette - Enhanced Visual Identity
val ElectricCyan = Color(0xFF00E5FF)
val Cyan40 = Color(0xFF0284C7)
val Cyan80 = Color(0xFF38BDF8)

val NeonIndigo = Color(0xFF6366F1)
val Indigo40 = Color(0xFF4338CA)
val Indigo80 = Color(0xFF818CF8)

val CyberViolet = Color(0xFFA855F7)
val Violet40 = Color(0xFF7C3AED)
val Violet80 = Color(0xFFA78BFA)

// Deep Space Obsidian Surfaces
val DarkBackground = Color(0xFF080C14)
val DarkSurface = Color(0xFF0F172A)
val DarkSurfaceVariant = Color(0xFF192237)
val DarkBorder = Color(0xFF26354D)
val DarkGlowBorder = Color(0xFF38BDF8).copy(alpha = 0.35f)

val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightBorder = Color(0xFFE2E8F0)

// Semantic State Indicators
val StatusSuccess = Color(0xFF10B981)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFF43F5E)
val StatusInfo = Color(0xFF38BDF8)

// Radiant Gradients for Cards and Headers
val CyberHeaderGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF0F172A),
        Color(0xFF1A2035),
        Color(0xFF1E1B4B)
    )
)

val NeonBadgeGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF00E5FF),
        Color(0xFF6366F1)
    )
)

val GlowCardGradient = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF151D2F),
        Color(0xFF0E1524)
    )
)
