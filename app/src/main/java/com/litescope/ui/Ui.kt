package com.litescope.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.litescope.view.Palette

/**
 * Small styling helpers so the settings page and the activity follow the same accent/background
 * theme as the scopes, instead of being locked to the app's default dark palette.
 */
object Ui {

    /** Rounded surface with an optional outline, built from the current palette. */
    fun surface(context: Context, fill: Int, stroke: Int?, radiusDp: Float): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp * density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (stroke != null) setStroke((density).toInt().coerceAtLeast(1), stroke)
        }
    }

    /** Card behind a settings section. */
    fun card(context: Context, palette: Palette): GradientDrawable =
        surface(context, palette.panel, palette.alpha(palette.accent, 0.16f), 14f)

    /** Option chip, selected or not. */
    fun chip(context: Context, palette: Palette, selected: Boolean): GradientDrawable = if (selected) {
        surface(context, palette.accent, null, 16f)
    } else {
        surface(context, palette.alpha(palette.text, 0.06f), palette.alpha(palette.text, 0.14f), 16f)
    }

    fun chipTextColor(palette: Palette, selected: Boolean): Int =
        if (selected) palette.bg else palette.dim

    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)

    /**
     * Buttons derive their fill, text and ripple colours from the accent theme overlay, so only the
     * shape needs a nudge here.
     */
    fun styleButton(button: MaterialButton, palette: Palette, context: Context) {
        button.isAllCaps = false
        button.cornerRadius = (16 * context.resources.displayMetrics.density).toInt()
        button.letterSpacing = 0f
    }

    /**
     * Material 3 switches already render the Android 14 look from the theme (primary thumb, tonal
     * track), so they only need to be left alone - the accent theme overlay recolours them.
     */
    fun styleSwitch(toggle: SwitchCompat, palette: Palette) {
        toggle.isAllCaps = false
    }

    fun styleTitle(view: TextView, palette: Palette) {
        view.setTextColor(palette.text)
    }

    fun styleSummary(view: TextView, palette: Palette) {
        view.setTextColor(palette.dim)
    }
}
