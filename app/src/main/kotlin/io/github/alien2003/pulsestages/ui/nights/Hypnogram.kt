package io.github.alien2003.pulsestages.ui.nights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EpochResult
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.ui.common.formatDuration
import io.github.alien2003.pulsestages.ui.common.formatTime
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.theme.spacing
import io.github.alien2003.pulsestages.ui.theme.stageColor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val BAR_FILL = 0.7f
private const val GUIDE_STROKE_PX = 1f
private const val GAP_ALPHA = 0.35f
private const val SELECTION_ALPHA = 0.22f
private const val RAW_ALPHA = 0.7f

/** The label column is as wide as its widest label (short stage names, heart-rate numbers), at most this wide. */
private val LABEL_COLUMN_MAX_WIDTH = 96.dp
private val HYPNOGRAM_HEIGHT = 160.dp
private val HR_HEIGHT = 120.dp
private val AXIS_PADDING = 4.dp
private val HATCH_SPACING = 6.dp
private val SMOOTH_STROKE = 2.dp
private val RAW_STROKE = 1.dp
private val STEP_STROKE = 1.dp
private val SELECTION_MIN_WIDTH = 2.dp

/**
 * The time window of a night chart and the zone offset its hour ticks are shown in.
 *
 * @property startMs left edge (session start).
 * @property endMs right edge (session end).
 * @property zoneOffsetSec zone offset of the session start.
 */
data class ChartWindow(
    val startMs: Long,
    val endMs: Long,
    val zoneOffsetSec: Int,
)

/**
 * Heart rate of a night for the curve under the hypnogram.
 *
 * @property epochs the epochs of the estimate (raw and smoothed heart rate per epoch).
 * @property epochMs epoch length.
 */
data class HeartRateSeries(
    val epochs: List<EpochResult>,
    val epochMs: Long,
)

/**
 * The night charts on one time axis: the hypnogram (one row per stage, awake on top; gaps without a stage are a
 * hatched band) and, when [heartRate] is given, the raw and smoothed heart rate below it. A tap or a horizontal drag
 * on either chart reports the time under the finger through [onSelectTime]; the [selected] epoch `[start, end)` is
 * highlighted on both. Colours come from the theme.
 */
@Composable
fun NightCharts(
    stages: List<StageInterval>,
    window: ChartWindow,
    heartRate: HeartRateSeries?,
    selected: Pair<Long, Long>?,
    onSelectTime: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val hrValues = remember(heartRate) { heartRate?.epochs?.flatMap { listOf(it.hrRaw, it.hrSmooth) }.orEmpty() }
    val range = remember(hrValues) { hrRange(hrValues) }
    // The hour labels grow with the font scale.
    val axisHeight =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelSmall.lineHeight
                .toDp()
        } + AXIS_PADDING
    Row(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.widthIn(max = LABEL_COLUMN_MAX_WIDTH).width(IntrinsicSize.Max).padding(end = AXIS_PADDING)) {
            StageRowLabels(Modifier.height(HYPNOGRAM_HEIGHT))
            if (heartRate != null && range != null) HeartRateLabels(range, Modifier.height(HR_HEIGHT))
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .then(if (onSelectTime == null) Modifier else Modifier.selectionInput(window, onSelectTime)),
        ) {
            HypnogramCanvas(stages, window, selected, Modifier.fillMaxWidth().height(HYPNOGRAM_HEIGHT))
            if (heartRate != null && range != null) {
                HeartRateCanvas(heartRate, range, window, selected, Modifier.fillMaxWidth().height(HR_HEIGHT))
            }
            TimeAxisLabels(window, Modifier.fillMaxWidth().height(axisHeight))
        }
    }
}

/** Taps and horizontal drags report the time under the pointer. */
private fun Modifier.selectionInput(
    window: ChartWindow,
    onSelectTime: (Long) -> Unit,
): Modifier =
    pointerInput(window) {
        detectTapGestures { offset -> onSelectTime(TimeAxis(window.startMs, window.endMs, size.width.toFloat()).time(offset.x)) }
    }.pointerInput(window) {
        val axis = { TimeAxis(window.startMs, window.endMs, size.width.toFloat()) }
        detectHorizontalDragGestures(
            onDragStart = { offset -> onSelectTime(axis().time(offset.x)) },
        ) { change, _ ->
            change.consume()
            onSelectTime(axis().time(change.position.x))
        }
    }

/** Short stage names fit the label column in every language and at large font scales; screen readers get the full name. */
@Composable
private fun StageRowLabels(modifier: Modifier) {
    Column(modifier = modifier) {
        HYPNOGRAM_ROWS.forEach { stage ->
            val fullName = stringResource(stage.labelRes())
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = stringResource(stage.shortLabelRes()),
                    modifier = Modifier.semantics { contentDescription = fullName },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeartRateLabels(
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier,
) {
    // Numbers only at the top and bottom, the unit once in the middle: nothing is cut off at large font scales.
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        val style = MaterialTheme.typography.labelSmall
        Text(range.endInclusive.roundToInt().toString(), style = style, maxLines = 1)
        Text(stringResource(R.string.night_chart_hr_label), style = style)
        Text(range.start.roundToInt().toString(), style = style, maxLines = 1)
    }
}

@Composable
private fun HypnogramCanvas(
    stages: List<StageInterval>,
    window: ChartWindow,
    selected: Pair<Long, Long>?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.cd_hypnogram, hypnogramSummary(stages))
    val ticks = remember(window) { hourTicks(window.startMs, window.endMs, window.zoneOffsetSec) }
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val axis = TimeAxis(window.startMs, window.endMs, size.width)
        drawTicks(ticks, axis, colors.outlineVariant)
        HYPNOGRAM_ROWS.indices.forEach { row ->
            val y = rowCenterY(row, size.height)
            drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = GUIDE_STROKE_PX)
        }
        val bars = hypnogramBars(stages, axis)
        bars.filter { it.row == null }.forEach { drawGap(it, colors.outlineVariant) }
        bars.zipWithNext().forEach { (a, b) ->
            val from = a.row
            val to = b.row
            if (from != null && to != null && from != to) {
                drawLine(
                    color = colors.onSurfaceVariant,
                    start = Offset(b.x0, rowCenterY(from, size.height)),
                    end = Offset(b.x0, rowCenterY(to, size.height)),
                    strokeWidth = STEP_STROKE.toPx(),
                )
            }
        }
        val rowHeight = size.height / HYPNOGRAM_ROWS.size
        bars.forEach { bar ->
            val row = bar.row ?: return@forEach
            drawRect(
                color = colors.stageColor(bar.stage),
                topLeft = Offset(bar.x0, row * rowHeight + rowHeight * (1 - BAR_FILL) / 2),
                size = Size(bar.x1 - bar.x0, rowHeight * BAR_FILL),
            )
        }
        selected?.let { drawSelection(it, axis, colors.onSurface) }
    }
}

@Composable
private fun HeartRateCanvas(
    heartRate: HeartRateSeries,
    range: ClosedFloatingPointRange<Double>,
    window: ChartWindow,
    selected: Pair<Long, Long>?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val raw = remember(heartRate) { curveSegments(heartRate.epochs, heartRate.epochMs) { it.hrRaw } }
    val smooth = remember(heartRate) { curveSegments(heartRate.epochs, heartRate.epochMs) { it.hrSmooth } }
    val ticks = remember(window) { hourTicks(window.startMs, window.endMs, window.zoneOffsetSec) }
    val description = stringResource(R.string.cd_hr_chart, range.start.roundToInt(), range.endInclusive.roundToInt())
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val axis = TimeAxis(window.startMs, window.endMs, size.width)
        drawTicks(ticks, axis, colors.outlineVariant)
        listOf(0f, size.height / 2, size.height).forEach { y ->
            drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = GUIDE_STROKE_PX)
        }
        drawCurve(raw, axis, range, colors.outline.copy(alpha = RAW_ALPHA), RAW_STROKE.toPx())
        drawCurve(smooth, axis, range, colors.secondary, SMOOTH_STROKE.toPx())
        selected?.let { drawSelection(it, axis, colors.onSurface) }
    }
}

@Composable
private fun TimeAxisLabels(
    window: ChartWindow,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ticks = remember(window) { hourTicks(window.startMs, window.endMs, window.zoneOffsetSec) }
    val labels = remember(ticks, window) { ticks.map { localDateTime(it, window.zoneOffsetSec).formatTime() } }
    Canvas(modifier = modifier) {
        if (ticks.isEmpty()) return@Canvas
        val axis = TimeAxis(window.startMs, window.endMs, size.width)
        val layouts = labels.map { measurer.measure(it, style) }
        val widest = layouts.maxOf { it.size.width }.toFloat()
        val spacing = if (ticks.size > 1) axis.x(ticks[1]) - axis.x(ticks[0]) else size.width
        val every = max(1, ceil(widest * LABEL_GAP_FACTOR / max(spacing, 1f)).toInt())
        ticks.forEachIndexed { index, tick ->
            if (index % every != 0) return@forEachIndexed
            val layout = layouts[index]
            val x = (axis.x(tick) - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width))
            drawText(layout, topLeft = Offset(x, 0f))
        }
    }
}

private const val LABEL_GAP_FACTOR = 1.5f

private fun DrawScope.drawTicks(
    ticks: List<Long>,
    axis: TimeAxis,
    color: Color,
) {
    ticks.forEach { tick ->
        val x = axis.x(tick)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = GUIDE_STROKE_PX)
    }
}

/** A gap (no stage) as a translucent band over all rows with diagonal hatching. */
private fun DrawScope.drawGap(
    bar: HypnogramBar,
    color: Color,
) {
    drawRect(color.copy(alpha = GAP_ALPHA), topLeft = Offset(bar.x0, 0f), size = Size(bar.x1 - bar.x0, size.height))
    val step = HATCH_SPACING.toPx()
    clipRect(left = bar.x0, top = 0f, right = bar.x1, bottom = size.height) {
        var x = bar.x0 - size.height
        while (x < bar.x1) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = GUIDE_STROKE_PX)
            x += step
        }
    }
}

private fun DrawScope.drawSelection(
    selected: Pair<Long, Long>,
    axis: TimeAxis,
    color: Color,
) {
    val x0 = axis.x(selected.first)
    val width = max(axis.x(selected.second) - x0, SELECTION_MIN_WIDTH.toPx())
    drawRect(color.copy(alpha = SELECTION_ALPHA), topLeft = Offset(x0, 0f), size = Size(width, size.height))
    drawLine(color, Offset(x0 + width / 2, 0f), Offset(x0 + width / 2, size.height), strokeWidth = GUIDE_STROKE_PX)
}

private fun DrawScope.drawCurve(
    segments: List<List<Pair<Long, Double>>>,
    axis: TimeAxis,
    range: ClosedFloatingPointRange<Double>,
    color: Color,
    strokePx: Float,
) {
    segments.forEach { points ->
        if (points.size == 1) {
            val (t, v) = points.single()
            drawCircle(color, radius = strokePx, center = Offset(axis.x(t), valueY(v, range, size.height)))
            return@forEach
        }
        val path = Path()
        points.forEachIndexed { index, (t, v) ->
            val x = axis.x(t)
            val y = valueY(v, range, size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = strokePx))
    }
}

/** "Deep 1 h 20 min, REM ..." for the content description of a hypnogram. */
@Composable
private fun hypnogramSummary(stages: List<StageInterval>): String = stageMinutesOf(stages).map { stageWithDuration(it) }.joinToString()

/** Colour legend with the minutes of each stage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageLegend(
    summary: List<StageMinutes>,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        summary.forEach { item ->
            LegendItem(MaterialTheme.colorScheme.stageColor(item.stage), stageWithDuration(item))
        }
    }
}

/** Legend of the heart-rate curve and the gap band. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(
    withHeartRate: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        LegendItem(colors.outlineVariant, stringResource(R.string.night_chart_gap))
        if (withHeartRate) {
            LegendItem(colors.outline, stringResource(R.string.night_chart_hr_raw), line = true)
            LegendItem(colors.secondary, stringResource(R.string.night_chart_hr_smooth), line = true)
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    text: String,
    line: Boolean = false,
    swatch: Dp = 12.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Box(
            modifier =
                if (line) {
                    Modifier.width(swatch).height(3.dp).background(color)
                } else {
                    Modifier.size(swatch).clip(CircleShape).background(color)
                },
        )
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/** A thin horizontal bar with the share of each stage (decorative: the same numbers are shown as text). */
@Composable
fun StageShareBar(
    summary: List<StageMinutes>,
    modifier: Modifier = Modifier,
) {
    val total = summary.sumOf { it.minutes }
    if (total <= 0.0) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.small),
    ) {
        summary.filter { it.minutes > 0.0 }.forEach { item ->
            Box(
                modifier =
                    Modifier
                        .weight((item.minutes / total).toFloat())
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.stageColor(item.stage)),
            )
        }
    }
}

/** "Deep 1 h 20 min". */
@Composable
internal fun stageWithDuration(item: StageMinutes): String =
    stringResource(R.string.stage_with_duration, stringResource(item.stage.labelRes()), formatDuration(item.minutes.roundToLong()))
