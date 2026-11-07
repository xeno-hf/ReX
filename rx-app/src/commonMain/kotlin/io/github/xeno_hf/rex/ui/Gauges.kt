package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 轉速刻度：低轉段壓縮（XTune 同款），常用轉速區才拉得開。上限 10／12／16（×1000），見 [raceV]／[raceH]。
 */
class RpmScale(
    /** 折線刻度：(rpm, 位置 0..1)，由小到大，首點 (0,0)、末點 (max,1)。 */
    private val points: List<Pair<Double, Double>>,
    val red: Double,
    /** 刻度數字（×1000）。 */
    val labels: List<Int>,
) {
    val max: Double = points.last().first

    fun frac(rpm: Double): Float {
        val x = rpm.coerceIn(0.0, max)
        for (i in 1 until points.size) {
            val (r1, f1) = points[i]
            if (x <= r1) {
                val (r0, f0) = points[i - 1]
                return (f0 + (x - r0) / (r1 - r0) * (f1 - f0)).toFloat()
            }
        }
        return 1f
    }

    /** frac 的反函數。 */
    fun rpmAt(f: Float): Double {
        for (i in 1 until points.size) {
            val (r1, f1) = points[i]
            if (f <= f1) {
                val (r0, f0) = points[i - 1]
                return r0 + (f - f0) / (f1 - f0) * (r1 - r0)
            }
        }
        return max
    }

    /** 每 500 轉一格；被壓縮的低轉段（相鄰標籤差超過 1000）不畫小刻度。 */
    val minorTicks: List<Double> = (0..(max / 500).toInt()).map { it * 500.0 }.filter { t ->
        t == 0.0 || t % 1000.0 == 0.0 && (t / 1000).toInt() in labels || run {
            val lo = labels.lastOrNull { it * 1000.0 <= t } ?: 0
            val hi = labels.firstOrNull { it * 1000.0 >= t } ?: lo
            hi - lo <= 1
        }
    }

    companion object {
        private const val E = 1.0 / 18000
        private const val D = 1.0 / 9000

        /**
         * 進階直向三檔：
         * 10K：<2000 rpm/18000，其後 rpm/9000−1/9；12K：<6000 rpm/18000，其後 rpm/9000−1/3；
         * 16K：<8000 rpm/18000，8000–10000 rpm/9000−4/9，其後 rpm/18000+1/9。紅字分別從 8／10／12 開始。
         */
        fun raceV(maxK: Int): RpmScale = when (maxK) {
            12 -> RpmScale(listOf(0.0 to 0.0, 6000.0 to 6000 * E, 12000.0 to 12000 * D - 1.0 / 3), 10_000.0, listOf(0, 2, 4, 6, 7, 8, 9, 10, 11, 12))
            16 -> RpmScale(listOf(0.0 to 0.0, 8000.0 to 8000 * E, 10000.0 to 10000 * D - 4.0 / 9, 16000.0 to 16000 * E + 1.0 / 9), 12_000.0,
                listOf(0, 2, 4, 6, 8, 9, 10, 12, 14, 16))
            else -> RpmScale(listOf(0.0 to 0.0, 2000.0 to 2000 * E, 10000.0 to 10000 * D - 1.0 / 9), 8_000.0, listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        }

        /** 進階橫向（XTune Gauge_Arrc_Race_H）：9 個數字固定位置，中間線性內插。 */
        private val H_POS = floatArrayOf(0.06759776f, 0.1877095f, 0.30502793f, 0.41396648f, 0.5223464f, 0.63184357f, 0.7435754f, 0.8586592f, 0.9854749f)

        fun raceH(maxK: Int): RpmScale {
            val (labels, red) = when (maxK) {
                12 -> listOf(0, 2, 4, 6, 8, 9, 10, 11, 12) to 11_000.0
                16 -> listOf(0, 4, 8, 9, 10, 11, 12, 14, 16) to 14_000.0
                else -> listOf(0, 3, 4, 5, 6, 7, 8, 9, 10) to 9_000.0
            }
            val f0 = H_POS.first(); val f1 = H_POS.last()
            return RpmScale(labels.mapIndexed { i, k -> k * 1000.0 to ((H_POS[i] - f0) / (f1 - f0)).toDouble() }, red, labels)
        }

        /** 巡航：線性。 */
        fun linear(maxK: Int): RpmScale = RpmScale(listOf(0.0 to 0.0, maxK * 1000.0 to 1.0), raceV(maxK).red, (0..maxK).toList())
    }
}

/**
 * 刻度數字放大（XTune 同款）：轉速接近某個數字時，那個數字跟著放大，經過後縮回。
 * 前後 1000 轉內線性變化，最大放大 [peak] 倍（XTune 直向 1.35、橫向 1.6）；每格用動畫補間，畫面逐幀滑順。
 */
@Composable
fun rememberLabelZoom(scale: RpmScale, rpm: Double, peak: Float): FloatArray {
    val x = rpm.coerceIn(0.0, scale.max)
    return FloatArray(scale.labels.size) { i ->
        val d = kotlin.math.abs(x - scale.labels[i] * 1000.0)
        val target = if (d < 1000.0) 1f + (peak - 1f) * (1f - (d / 1000.0).toFloat()) else 1f
        androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(120), label = "zoom$i").value
    }
}

/** 峰值保持：超過就更新，停留 [holdNs] 後回到目前值。 */
class PeakHold(private val holdNs: Long = 2_500_000_000L) {
    var value = 0.0; private set
    private var t = 0L
    fun update(v: Double, now: Long): Double {
        if (v >= value || now - t > holdNs) { value = v; t = now }
        return value
    }
}

/** 進階儀表：0 在正下、順時針經正左（4）、正上（7）到正右（10），右下象限留給時速（XTune Race_V：startAngle 90、sweep 270）。 */
const val RACE_START = 90f
const val RACE_SWEEP = 270f
/** 巡航儀表：0 在左下、10 在右下，底部開口 120°（XTune DB_Simple：startAngle 150、sweep 240）。 */
const val CRUISE_START = 150f
const val CRUISE_SWEEP = 240f

private fun segColor(mid: Float, rpmMid: Double, scale: RpmScale): Color =
    if (rpmMid >= scale.red) RxColors.danger else lerp(RxColors.brand2, RxColors.brand, mid.coerceIn(0f, 1f))

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, size: TextUnit, color: Color, italic: Boolean = false) {
    val r = tm.measure(text, TextStyle(fontFamily = Digits, fontWeight = FontWeight.ExtraBold, fontSize = size, color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = deg.toDouble() * kotlin.math.PI / 180.0
    return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
}

/**
 * 弧的外接幾何：依起點／掃角算出弧在單位圓上的外框，塞進畫布後回傳圓心與半徑。
 * 270° 底開口 → 高 R(1+sin45°)；240° → 高 1.5R；進階的 90°+270° 等於整圓外框。
 */
private fun DrawScope.arcGeom(margin: Float, start: Float, sweep: Float): Pair<Offset, Float> {
    val angles = buildList {
        add(start); add(start + sweep)
        var a = (kotlin.math.ceil(start / 90.0) * 90).toFloat()
        while (a < start + sweep) { add(a); a += 90f }
    }
    val xs = angles.map { cos(it.toDouble() * kotlin.math.PI / 180.0).toFloat() } + 0f
    val ys = angles.map { sin(it.toDouble() * kotlin.math.PI / 180.0).toFloat() } + 0f
    val (x0, x1) = xs.min() to xs.max()
    val (y0, y1) = ys.min() to ys.max()
    val r = min((size.width - 2 * margin) / (x1 - x0), (size.height - 2 * margin) / (y1 - y0))
    val cx = margin + ((size.width - 2 * margin) - r * (x1 - x0)) / 2 - x0 * r
    return Offset(cx, margin - y0 * r) to r
}

/** 進階儀表（直向）：分段 LED 弧＋紅區＋峰值標；[unit] 畫在弧起點（0）外側旁邊。 */
@Composable
fun RpmArcGauge(
    rpm: Double, peak: Double, scale: RpmScale, modifier: Modifier = Modifier,
    start: Float = RACE_START, sweep: Float = RACE_SWEEP, unit: String? = null, zoom: FloatArray? = null,
) {
    val tm = rememberTextMeasurer(48)
    Canvas(modifier) {
        val (c, outer) = arcGeom(8.dp.toPx(), start, sweep)
        val band = outer * 0.13f
        val rm = outer - band / 2
        val segs = 54
        val segSweep = sweep / segs
        val gap = 1.1f
        val f = scale.frac(rpm)
        val tl = Offset(c.x - rm, c.y - rm)
        val sz = Size(rm * 2, rm * 2)
        for (i in 0 until segs) {
            val mid = (i + 0.5f) / segs
            val a0 = start + segSweep * i
            val rpmMid = scale.rpmAt(mid)
            val lit = mid <= f
            val col = when {
                lit -> segColor(mid, rpmMid, scale)
                rpmMid >= scale.red -> RxColors.danger.copy(alpha = 0.22f)
                else -> RxColors.track
            }
            if (lit) drawArc(col.copy(alpha = 0.22f), a0 - 0.6f, segSweep + 1.2f, false, Offset(c.x - rm, c.y - rm), sz, style = Stroke(band * 1.55f))
            drawArc(col, a0 + gap / 2, segSweep - gap, false, tl, sz, style = Stroke(band))
        }
        // 外緣細環＋紅區
        val ro = outer + 4.dp.toPx()
        drawArc(RxColors.line, start, sweep, false, Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2), style = Stroke(1.5.dp.toPx()))
        val fr = scale.frac(scale.red)
        drawArc(RxColors.danger, start + sweep * fr, sweep * (1 - fr), false, Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        // 內圈刻度
        val ri = outer - band - 5.dp.toPx()
        drawArc(RxColors.line, start, sweep, false, Offset(c.x - ri, c.y - ri), Size(ri * 2, ri * 2), style = Stroke(1.dp.toPx()))
        for (t in scale.minorTicks) {
            val major = t % 1000.0 == 0.0
            val a = start + sweep * scale.frac(t)
            val len = if (major) 9.dp.toPx() else 5.dp.toPx()
            drawLine(if (t >= scale.red) RxColors.danger else if (major) RxColors.text else RxColors.muted,
                polar(c, ri, a), polar(c, ri - len, a), strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx())
        }
        val lr = ri - 22.dp.toPx()
        val ls = (outer * 0.14f).toSp()
        scale.labels.forEachIndexed { i, k ->
            val a = start + sweep * scale.frac(k * 1000.0)
            label(tm, "$k", polar(c, lr, a), ls * (zoom?.getOrNull(i) ?: 1f), if (k * 1000.0 >= scale.red) RxColors.danger else RxColors.text, italic = true)
        }
        // 單位字：緊貼外環 0 端點右側（XTune x1000RPM 的位置）
        unit?.let {
            val t = tm.measure(it, TextStyle(fontFamily = Digits, fontWeight = FontWeight.SemiBold, fontSize = (outer * 0.075f).toSp(),
                color = RxColors.muted, fontStyle = FontStyle.Italic))
            val p = polar(c, ro, start)
            drawText(t, topLeft = Offset(p.x + 6.dp.toPx(), min(p.y - t.size.height / 2f, size.height - t.size.height)))
        }
        // 峰值
        if (peak > rpm + 150) {
            val a = start + sweep * scale.frac(peak)
            drawLine(RxColors.partner, polar(c, outer + 2.dp.toPx(), a), polar(c, outer - band - 2.dp.toPx(), a), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}


/**
 * 橫向主儀表：由左下往右上竄升的斜帶（XTune 橫向同款語彙）。
 * 幾何也給版面用：[bandTop]/[bandBottom] 讓其他元件避開斜帶。
 */
class Sweep(val w: Float, val h: Float) {
    fun x(t: Float) = w * (0.015f + 0.925f * t)
    fun yc(t: Float) = h * (0.68f - 0.46f * t.pow(1.6f))
    fun th(t: Float) = h * (0.045f + 0.075f * t)
    fun bandTop(t: Float) = yc(t) - th(t) / 2
    fun bandBottom(t: Float) = yc(t) + th(t) / 2
    fun tAtX(x: Float) = ((x / w - 0.015f) / 0.925f).coerceIn(0f, 1f)
}

@Composable
fun RpmSweepGauge(rpm: Double, peak: Double, scale: RpmScale, modifier: Modifier = Modifier, zoom: FloatArray? = null) {
    val tm = rememberTextMeasurer(48)
    Canvas(modifier) {
        val g = Sweep(size.width, size.height)
        val n = 72
        val f = scale.frac(rpm)
        for (i in 0 until n) {
            val t0 = i / n.toFloat()
            val t1 = (i + 0.62f) / n
            val mid = (t0 + t1) / 2
            val rpmMid = scale.rpmAt(mid)
            val lit = mid <= f
            val col = when {
                lit -> segColor(mid, rpmMid, scale)
                rpmMid >= scale.red -> RxColors.danger.copy(alpha = 0.25f)
                else -> RxColors.track
            }
            val sk0 = g.th(t0) * 0.45f
            val sk1 = g.th(t1) * 0.45f
            val path = Path().apply {
                moveTo(g.x(t0) + sk0, g.bandTop(t0))
                lineTo(g.x(t1) + sk1, g.bandTop(t1))
                lineTo(g.x(t1), g.bandBottom(t1))
                lineTo(g.x(t0), g.bandBottom(t0))
                close()
            }
            if (lit) drawPath(path, col.copy(alpha = 0.25f), style = Stroke(5.dp.toPx()))
            drawPath(path, col)
        }
        // 上緣尺規
        val off = 7.dp.toPx()
        val line = Path().apply {
            moveTo(g.x(0f), g.bandTop(0f) - off)
            var t = 0f
            while (t <= 1f) { lineTo(g.x(t) + g.th(t) * 0.45f, g.bandTop(t) - off); t += 0.01f }
        }
        drawPath(line, RxColors.text.copy(alpha = 0.8f), style = Stroke(2.dp.toPx()))
        for (r in scale.minorTicks) {
            val t = scale.frac(r)
            val major = r % 1000.0 == 0.0
            val x = g.x(t) + g.th(t) * 0.45f
            val y = g.bandTop(t) - off
            drawLine(if (r >= scale.red) RxColors.danger else RxColors.text, Offset(x, y), Offset(x, y - if (major) 9.dp.toPx() else 5.dp.toPx()),
                strokeWidth = if (major) 2.5.dp.toPx() else 1.5.dp.toPx())
        }
        scale.labels.forEachIndexed { i, k ->
            val t = scale.frac(k * 1000.0)
            val s = (18 + 13 * t).dp.toSp() * (zoom?.getOrNull(i) ?: 1f)
            val x = g.x(t) + g.th(t) * 0.45f
            label(tm, "$k", Offset(x, g.bandTop(t) - off - 14.dp.toPx() - (4 * t).dp.toPx()), s,
                if (k * 1000.0 >= scale.red) RxColors.danger else RxColors.text, italic = true)
        }
        if (peak > rpm + 150) {
            val t = scale.frac(peak)
            drawLine(RxColors.partner, Offset(g.x(t) + g.th(t) * 0.5f, g.bandTop(t) - 2.dp.toPx()), Offset(g.x(t), g.bandBottom(t) + 2.dp.toPx()),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** 巡航儀表：細梳齒環（XTune 巡航同款），線性刻度。 */
@Composable
fun CombRingGauge(rpm: Double, scale: RpmScale, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer(48)
    Canvas(modifier) {
        val (c, outer) = arcGeom(6.dp.toPx(), CRUISE_START, CRUISE_SWEEP)
        val lin = { r: Double -> (r / scale.max).toFloat().coerceIn(0f, 1f) }
        val f = lin(rpm)
        val r1 = outer * 0.80f
        val r2 = outer * 0.97f
        val steps = (scale.max / 100).toInt()
        for (i in 0..steps) {
            val fr = i / steps.toFloat()
            val r = i * 100.0
            val major = i % 10 == 0
            val lit = fr <= f && rpm > 50
            val a = CRUISE_START + CRUISE_SWEEP * fr
            val col = when {
                lit -> segColor(fr, r, scale)
                r >= scale.red -> RxColors.danger.copy(alpha = 0.45f)
                else -> RxColors.brand.copy(alpha = if (RxColors.palette.light) 0.28f else 0.22f)
            }
            if (lit) drawLine(col.copy(alpha = 0.25f), polar(c, r1, a), polar(c, r2, a), strokeWidth = 5.dp.toPx())
            drawLine(col, polar(c, if (major) r1 - 6.dp.toPx() else r1, a), polar(c, r2, a), strokeWidth = if (major) 2.5.dp.toPx() else 1.4.dp.toPx())
        }
        drawArc(RxColors.text.copy(alpha = 0.55f), CRUISE_START, CRUISE_SWEEP, false, Offset(c.x - outer, c.y - outer), Size(outer * 2, outer * 2),
            style = Stroke(2.dp.toPx()))
        val fr = lin(scale.red)
        drawArc(RxColors.danger, CRUISE_START + CRUISE_SWEEP * fr, CRUISE_SWEEP * (1 - fr), false, Offset(c.x - outer, c.y - outer), Size(outer * 2, outer * 2),
            style = Stroke(3.dp.toPx()))
        val ri = outer * 0.72f
        drawArc(RxColors.line, CRUISE_START, CRUISE_SWEEP, false, Offset(c.x - ri, c.y - ri), Size(ri * 2, ri * 2), style = Stroke(1.dp.toPx()))
        val ls = (outer * 0.145f).toSp()
        for (k in 0..(scale.max / 1000).toInt()) {
            val a = CRUISE_START + CRUISE_SWEEP * lin(k * 1000.0)
            label(tm, "$k", polar(c, outer * 0.88f, a), ls, if (k * 1000.0 >= scale.red) RxColors.danger else RxColors.text, italic = true)
        }
        // 頂端缺口（XTune 巡航頂部的梯形框）
        val top = c.y - ri + 4.dp.toPx()
        val wTop = outer * 0.26f
        val wBot = outer * 0.18f
        val hh = outer * 0.10f
        val notch = Path().apply {
            moveTo(c.x - wTop / 2, top); lineTo(c.x - wBot / 2, top + hh); lineTo(c.x + wBot / 2, top + hh); lineTo(c.x + wTop / 2, top)
        }
        drawPath(notch, RxColors.muted.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 水平填充條（油門等）。 */
@Composable
fun FillBar(frac: Float, modifier: Modifier = Modifier, color: Color? = null) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(RxColors.track, cornerRadius = r)
        val w = size.width * frac.coerceIn(0f, 1f)
        if (w > 0f) {
            val brush = color?.let { Brush.horizontalGradient(listOf(it, it)) }
                ?: Brush.horizontalGradient(listOf(RxColors.brand2, RxColors.brand), endX = size.width)
            drawRoundRect(brush, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** 中心零點的雙向條（電機電流：一邊放電一邊充電）。 */
@Composable
fun CenterBar(frac: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(RxColors.track, cornerRadius = r)
        val mid = size.width / 2
        val w = mid * frac.coerceIn(-1f, 1f)
        if (w > 0) drawRoundRect(color, Offset(mid, 0f), Size(w, size.height), r)
        else if (w < 0) drawRoundRect(color, Offset(mid + w, 0f), Size(-w, size.height), r)
        drawLine(RxColors.muted, Offset(mid, -2f), Offset(mid, size.height + 2f), strokeWidth = 1.5.dp.toPx())
    }
}

/** 進階橫向的斜條（XTune Gauge_Arrc_Slop）：上方填充弧、下方細導線＋中點刻度。 */
@Composable
fun SlopBar(frac: Float, modifier: Modifier = Modifier, color: Color? = null) {
    Canvas(modifier) {
        val thick = 6.dp.toPx()
        val off = 9.dp.toPx()
        fun pt(t: Float, dy: Float = 0f) = Offset(size.width * t, (size.height - off - thick) * (1f - t.pow(1.45f)) + thick / 2 + dy)
        fun curve(t1: Float, dy: Float = 0f) = Path().apply {
            moveTo(pt(0f, dy).x, pt(0f, dy).y)
            var t = 0f
            while (t < t1) { t = min(t1, t + 0.02f); lineTo(pt(t, dy).x, pt(t, dy).y) }
        }
        drawPath(curve(1f), RxColors.track, style = Stroke(thick, cap = StrokeCap.Round))
        val f = frac.coerceIn(0f, 1f)
        val brush = color?.let { SolidColor(it) } ?: Brush.horizontalGradient(listOf(RxColors.brand2, RxColors.brand), endX = size.width)
        if (f > 0f) drawPath(curve(f), brush, style = Stroke(thick, cap = StrokeCap.Round))
        drawPath(curve(1f, off), RxColors.text.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx()))
        for (t in listOf(0f, 0.5f, 1f)) {
            val p = pt(t, off)
            drawLine(RxColors.text.copy(alpha = 0.7f), p, Offset(p.x, p.y + 5.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** 小折線（監控磚背景）。[values] 由舊到新。 */
@Composable
fun Sparkline(values: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in values) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        val flat = hi - lo <= 1e-6f
        val dx = size.width / (values.size - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val y = if (flat) size.height * 0.5f else size.height - (v - lo) / span * size.height
            if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y)
        }
        val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)))
        drawPath(p, color.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

enum class StatGlyph { Coolant, Battery }

/** 底列小圖示：水溫＝溫度計＋兩道水波（XTune icon_overheater 同語彙）；電瓶＝雙極柱方塊、左負右正（同原版）。 */
@Composable
fun StatIcon(kind: StatGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        when (kind) {
            StatGlyph.Coolant -> {
                val x = w * 0.42f
                drawLine(color, Offset(x, h * 0.08f), Offset(x, h * 0.56f), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.11f, center = Offset(x, h * 0.6f))
                for (y in listOf(0.18f, 0.3f, 0.42f)) {
                    drawLine(color, Offset(x + w * 0.12f, h * y), Offset(x + w * 0.32f, h * y), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
                }
                val seg = w * 0.92f / 4
                for (y in listOf(0.8f, 0.94f)) {
                    val wave = Path().apply {
                        moveTo(w * 0.04f, h * y)
                        for (i in 0 until 4) relativeQuadraticTo(seg / 2, if (i % 2 == 0) -h * 0.08f else h * 0.08f, seg, 0f)
                    }
                    drawPath(wave, color, style = Stroke(sw * 0.9f, cap = StrokeCap.Round))
                }
            }
            StatGlyph.Battery -> {
                val top = h * 0.3f
                drawRoundRect(color, Offset(w * 0.06f, top), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.08f), style = Stroke(sw))
                for (cx in listOf(0.28f, 0.72f)) drawRect(color, Offset(w * (cx - 0.08f), h * 0.18f), Size(w * 0.16f, h * 0.12f))
                val my = top + h * 0.29f
                drawLine(color, Offset(w * 0.2f, my), Offset(w * 0.36f, my), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.64f, my), Offset(w * 0.8f, my), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, my - w * 0.08f), Offset(w * 0.72f, my + w * 0.08f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

/** 引擎故障燈（MIL）：ISO 2575 引擎符號，自己畫、不搬原廠圖檔。 */
@Composable
fun EngineLamp(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val st = Stroke(w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        val body = Path().apply {
            moveTo(w * 0.24f, h * 0.40f); lineTo(w * 0.64f, h * 0.40f); lineTo(w * 0.72f, h * 0.48f)
            lineTo(w * 0.84f, h * 0.48f); lineTo(w * 0.84f, h * 0.72f); lineTo(w * 0.72f, h * 0.72f)
            lineTo(w * 0.64f, h * 0.80f); lineTo(w * 0.34f, h * 0.80f); lineTo(w * 0.24f, h * 0.70f); close()
        }
        drawPath(body, color, style = st)
        // 左側起動馬達、上方加油蓋、右側排氣
        drawLine(color, p(0.10f, 0.56f), p(0.24f, 0.56f), st.width, StrokeCap.Round)
        drawLine(color, p(0.10f, 0.45f), p(0.10f, 0.67f), st.width, StrokeCap.Round)
        drawLine(color, p(0.44f, 0.40f), p(0.44f, 0.28f), st.width, StrokeCap.Round)
        drawLine(color, p(0.32f, 0.26f), p(0.56f, 0.26f), st.width * 1.4f, StrokeCap.Round)
        drawLine(color, p(0.84f, 0.60f), p(0.92f, 0.60f), st.width, StrokeCap.Round)
        drawLine(color, p(0.92f, 0.52f), p(0.92f, 0.68f), st.width, StrokeCap.Round)
    }
}

/** 水晶狀態燈：菱形＋十字紋。 */
@Composable
fun CrystalDot(color: Color, glow: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val w = size.width / 2
        val h = size.height / 2
        val gem = Path().apply { moveTo(c.x, c.y - h); lineTo(c.x + w * 0.62f, c.y); lineTo(c.x, c.y + h); lineTo(c.x - w * 0.62f, c.y); close() }
        if (glow) drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension * 0.62f)
        drawPath(gem, color)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x, c.y - h * 0.55f), Offset(c.x, c.y + h * 0.55f), strokeWidth = 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x - w * 0.32f, c.y), Offset(c.x + w * 0.32f, c.y), strokeWidth = 1.dp.toPx())
    }
}

/** Center is zero; each half is 500 W. Clip the fill, not the actual reading. */
@Composable
fun powerColor(value: Double?): Color = when {
    value == null || value == 0.0 -> RxColors.brandInk
    value > 0 -> RxColors.accent
    else -> RxColors.danger
}

@Composable
fun PowerBar(value: Double?, modifier: Modifier = Modifier) {
    val ink = powerColor(value)
    val track = RxColors.track
    val neutral = RxColors.muted
    Canvas(modifier) {
        drawRect(track)
        val center = size.width / 2f
        val end = center + center * DerivedChannels.fraction(value)
        if (end != center) drawRect(ink, Offset(minOf(center, end), 0f), Size(kotlin.math.abs(end - center), size.height))
        drawLine(neutral, Offset(center, 0f), Offset(center, size.height), 1.dp.toPx())
    }
}
