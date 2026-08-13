package com.meshchats.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Pure monochrome palette. No hue anywhere: identity must render identically on
 * every device, so Material You dynamic color is never used.
 *
 * Depth comes from surface steps plus hairlines, never shadows: in monochrome a
 * drop shadow reads as grey smudge.
 */

// Dark theme (default)
val Ink0 = Color(0xFF000000) // background
val Ink1 = Color(0xFF0B0B0B) // surface
val Ink2 = Color(0xFF151515) // surface high
val Ink3 = Color(0xFF232323) // surface max (outgoing bubbles, pressed states)

// Light theme
val Paper0 = Color(0xFFFFFFFF)
val Paper1 = Color(0xFFFAFAFA)
val Paper2 = Color(0xFFF1F1F1)
val Paper3 = Color(0xFFE7E7E7)

// Text / glyph opacity ladder, applied to onSurface in both themes.
const val AlphaPrimary = 1.0f
const val AlphaSecondary = 0.72f
const val AlphaMeta = 0.48f
const val AlphaDisabled = 0.32f
const val AlphaHairline = 0.14f
