package com.notrace.messenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Roboto ships with Android by default; swap FontFamily.Default for a
// bundled Inter variable font (res/font) once the asset is added.
val NoTraceTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal, // "400 body"
        fontSize = 16.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold, // "600 headers"
        fontSize = 22.sp,
    ),
)
