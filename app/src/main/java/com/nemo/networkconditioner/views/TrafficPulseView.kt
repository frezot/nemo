package com.nemo.networkconditioner.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.model.AppState
import kotlin.math.max

class TrafficPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    companion object {
        const val QUANTUM_MS = 250L
        const val STATS_TICKS_PER_COLUMN = 2
        private const val TILE_COUNT = 40
    }

    private enum class PulseType {
        EMPTY,
        TRAFFIC,
        DROP,
        DROP_STRONG,
        STALL
    }

    private val tileGapPx = dpToPx(3f)
    private val rowGapPx = dpToPx(5f)
    private val tileCornerPx = dpToPx(3f)
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.visualizerRail)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trafficColor = ContextCompat.getColor(context, R.color.visualizerTraffic)
    private val dropColor = ContextCompat.getColor(context, R.color.visualizerDrop)
    private val dropStrongColor = ContextCompat.getColor(context, R.color.visualizerDropStrong)
    private val stallColor = ContextCompat.getColor(context, R.color.visualizerStall)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTextColor()
        textAlign = Paint.Align.LEFT
        textSize = dpToPx(11f)
        isFakeBoldText = true
    }

    private val uploadPulses = Array(TILE_COUNT) { PulseType.EMPTY }
    private val downloadPulses = Array(TILE_COUNT) { PulseType.EMPTY }
    private val labelColumnWidthPx = dpToPx(6f)

    private var lastSentBytes = 0L
    private var lastReceivedBytes = 0L
    private var lastDroppedSentPackets = 0L
    private var lastDroppedReceivedPackets = 0L
    private var pendingSentBytes = 0L
    private var pendingReceivedBytes = 0L
    private var pendingDroppedSentPackets = 0L
    private var pendingDroppedReceivedPackets = 0L
    private var pendingStallUplink = false
    private var pendingStallDownlink = false
    private var pendingSampleCount = 0
    private var nextWriteIndex = 0
    private var renderedColumnCount = 0

    fun submitStats(
        sentBytes: Long,
        receivedBytes: Long,
        droppedSentPackets: Long,
        droppedReceivedPackets: Long,
        stallUplinkActive: Boolean,
        stallDownlinkActive: Boolean
    ) {
        if (lastSentBytes == 0L && lastReceivedBytes == 0L &&
            lastDroppedSentPackets == 0L && lastDroppedReceivedPackets == 0L &&
            pendingSampleCount == 0
        ) {
            lastSentBytes = sentBytes
            lastReceivedBytes = receivedBytes
            lastDroppedSentPackets = droppedSentPackets
            lastDroppedReceivedPackets = droppedReceivedPackets
            return
        }

        pendingSentBytes += max(0L, sentBytes - lastSentBytes)
        pendingReceivedBytes += max(0L, receivedBytes - lastReceivedBytes)
        pendingDroppedSentPackets += max(0L, droppedSentPackets - lastDroppedSentPackets)
        pendingDroppedReceivedPackets += max(0L, droppedReceivedPackets - lastDroppedReceivedPackets)
        pendingStallUplink = pendingStallUplink || stallUplinkActive
        pendingStallDownlink = pendingStallDownlink || stallDownlinkActive
        pendingSampleCount++

        lastSentBytes = sentBytes
        lastReceivedBytes = receivedBytes
        lastDroppedSentPackets = droppedSentPackets
        lastDroppedReceivedPackets = droppedReceivedPackets
    }

    fun maybeAdvance(state: AppState) {
        if (state == AppState.ready) {
            clear()
            return
        }

        if (pendingSampleCount < STATS_TICKS_PER_COLUMN) {
            return
        }

        val uploadPulse = resolvePulse(pendingSentBytes, pendingDroppedSentPackets, pendingStallUplink)
        val downloadPulse = resolvePulse(pendingReceivedBytes, pendingDroppedReceivedPackets, pendingStallDownlink)
        uploadPulses[nextWriteIndex] = uploadPulse
        downloadPulses[nextWriteIndex] = downloadPulse
        nextWriteIndex = (nextWriteIndex + 1) % TILE_COUNT
        renderedColumnCount++

        pendingSentBytes = 0L
        pendingReceivedBytes = 0L
        pendingDroppedSentPackets = 0L
        pendingDroppedReceivedPackets = 0L
        pendingStallUplink = false
        pendingStallDownlink = false
        pendingSampleCount = 0
        invalidate()
    }

    fun clear() {
        uploadPulses.fill(PulseType.EMPTY)
        downloadPulses.fill(PulseType.EMPTY)
        lastSentBytes = 0L
        lastReceivedBytes = 0L
        lastDroppedSentPackets = 0L
        lastDroppedReceivedPackets = 0L
        pendingSentBytes = 0L
        pendingReceivedBytes = 0L
        pendingDroppedSentPackets = 0L
        pendingDroppedReceivedPackets = 0L
        pendingStallUplink = false
        pendingStallDownlink = false
        pendingSampleCount = 0
        nextWriteIndex = 0
        renderedColumnCount = 0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dpToPx(40f).toInt()
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) {
            return
        }

        val rowHeight = (height - rowGapPx) / 2f
        drawLabel(canvas, "↑", 0f, rowHeight)
        drawLabel(canvas, "↓", rowHeight + rowGapPx, rowHeight)
        drawRow(canvas, uploadPulses, 0f, rowHeight)
        drawRow(canvas, downloadPulses, rowHeight + rowGapPx, rowHeight)
    }

    private fun resolvePulse(byteDelta: Long, droppedDelta: Long, stallActive: Boolean): PulseType {
        return when {
            stallActive && droppedDelta > 0 -> {
                if ((renderedColumnCount % 2) == 0) {
                    PulseType.STALL
                } else if (droppedDelta >= 3) {
                    PulseType.DROP_STRONG
                } else {
                    PulseType.DROP
                }
            }
            stallActive -> PulseType.STALL
            droppedDelta >= 3 -> PulseType.DROP_STRONG
            droppedDelta > 0 -> PulseType.DROP
            byteDelta > 0 -> PulseType.TRAFFIC
            else -> PulseType.EMPTY
        }
    }

    private fun drawRow(canvas: Canvas, pulses: Array<PulseType>, top: Float, rowHeight: Float) {
        val tileCount = pulses.size
        val contentWidth = width - labelColumnWidthPx - tileGapPx
        val tileWidth = (contentWidth - tileGapPx * (tileCount - 1)) / tileCount.toFloat()

        for (i in 0 until tileCount) {
            val left = labelColumnWidthPx + tileGapPx + i * (tileWidth + tileGapPx)
            val right = left + tileWidth
            val bottom = top + rowHeight
            canvas.drawRoundRect(left, top, right, bottom, tileCornerPx, tileCornerPx, railPaint)

            val pulseType = pulses[(nextWriteIndex + i) % tileCount]
            if (pulseType == PulseType.EMPTY) {
                continue
            }

            pulsePaint.color = when (pulseType) {
                PulseType.TRAFFIC -> trafficColor
                PulseType.DROP -> dropColor
                PulseType.DROP_STRONG -> dropStrongColor
                PulseType.STALL -> stallColor
                PulseType.EMPTY -> railPaint.color
            }
            canvas.drawRoundRect(left, top, right, bottom, tileCornerPx, tileCornerPx, pulsePaint)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, top: Float, rowHeight: Float) {
        val x = 0f
        val baseline = top + rowHeight / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, x, baseline, labelPaint)
    }

    private fun currentTextColor(): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}
