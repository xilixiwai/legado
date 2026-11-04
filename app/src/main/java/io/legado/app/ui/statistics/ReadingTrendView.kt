package io.legado.app.ui.statistics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.toReadDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Seven accessible day buttons, each drawing a strictly proportional duration bar. */
class ReadingTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dateFormat = DateTimeFormatter.ofPattern("MM/dd")
    private val density = resources.displayMetrics.density
    private val accent: Int
        get() = if (ColorUtils.calculateContrast(context.accentColor,
                ColorUtils.setAlphaComponent(context.backgroundColor, 255)) >= 3.0) {
            context.accentColor
        } else {
            context.primaryTextColor
        }

    init {
        orientation = HORIZONTAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setDays(days: List<ReadingTrendDay>, today: LocalDate, onSelect: (ReadingTrendDay) -> Unit) {
        removeAllViews()
        val ratios = readingTrendRatios(days)
        days.forEachIndexed { index, day ->
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(8), dp(2), dp(8))
                contentDescription = "${day.date}, ${day.readTime.toReadDuration()}" +
                    if (day.date == today) ", ${context.getString(R.string.read_overview_today)}" else ""
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            }
            column.addView(DurationBar(context, ratios[index], day.readTime > 0, accent),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(108)))
            column.addView(TextView(context).apply {
                text = if (day.date == today) context.getString(R.string.read_overview_today)
                    else day.date.format(dateFormat)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (day.date == today) accent else context.secondaryTextColor)
                setPadding(0, dp(10), 0, 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            column.setOnClickListener {
                select(index)
                onSelect(day)
            }
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        if (days.isNotEmpty()) {
            val index = days.indexOfFirst { it.date == today }.takeIf { it >= 0 } ?: days.lastIndex
            select(index)
            onSelect(days[index])
        }
    }

    private fun select(index: Int) {
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                isSelected = i == index
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (isSelected) ColorUtils.blendARGB(context.backgroundColor, accent, 0.12f)
                        else android.graphics.Color.TRANSPARENT)
                    if (isSelected) setStroke(dp(1), accent)
                }
            }
        }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    private class DurationBar(
        context: Context,
        private val ratio: Float,
        private val hasReading: Boolean,
        private val color: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val baseline = height - 6 * density
            val halfWidth = width * 0.25f
            val center = width / 2f
            paint.color = ColorUtils.setAlphaComponent(context.secondaryTextColor, 80)
            paint.strokeWidth = density
            canvas.drawLine(0f, baseline, width.toFloat(), baseline, paint)
            if (hasReading) {
                paint.color = color
                // No artificial minimum height: a 1-second reading stays 1/60 of a minute.
                val top = baseline * (1f - ratio)
                canvas.drawRect(center - halfWidth, top, center + halfWidth, baseline, paint)
                // Independent marker distinguishes a tiny positive duration from a zero day.
                canvas.drawCircle(center, height - 2 * density, 2 * density, paint)
            } else {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(center, height - 2 * density, 2 * density, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }
}
