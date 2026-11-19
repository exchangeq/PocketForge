package com.pocketforge.ai.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.R

/**
 * 内嵌思源黑体（已裁剪到常用字 + 全角标点），
 * 避免被 ROM 主题字体（手写体）替换，保证 UI 排版稳定。
 */
val PfFont = FontFamily(Font(R.font.pf_sans, FontWeight.Normal))

private fun base(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(
        fontFamily = PfFont,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = spacing.sp,
    )

val PfTypography = Typography(
    displaySmall = base(28f, 34f, FontWeight.Bold, -0.3f),
    headlineMedium = base(24f, 30f, FontWeight.Bold, -0.3f),
    titleLarge = base(20f, 27f, FontWeight.Bold, (-0.2f)),
    titleMedium = base(17f, 24f, FontWeight.Medium, 0f),
    titleSmall = base(15f, 21f, FontWeight.Medium, 0f),
    bodyLarge = base(15f, 24f, FontWeight.Normal, 0f),
    bodyMedium = base(14f, 22f, FontWeight.Normal, 0f),
    bodySmall = base(12.5f, 19f, FontWeight.Normal, 0.1f),
    labelLarge = base(13f, 18f, FontWeight.Medium, 0.2f),
    labelMedium = base(11.5f, 16f, FontWeight.Normal, 0.3f),
    labelSmall = base(10.5f, 15f, FontWeight.Normal, 0.3f),
)
