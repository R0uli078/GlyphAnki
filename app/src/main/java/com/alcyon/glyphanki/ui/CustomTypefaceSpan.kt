package com.alcyon.glyphanki.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) {
        apply(p)
    }

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
    }

    private fun apply(paint: Paint) {
        paint.typeface = typeface
        if (paint is TextPaint) {
            paint.isSubpixelText = false
            paint.letterSpacing = 0f
            paint.textScaleX = 1f
            paint.isFakeBoldText = false
        }
    }
}


