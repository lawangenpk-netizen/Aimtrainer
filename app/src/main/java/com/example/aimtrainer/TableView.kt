package com.example.aimtrainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Practice table. Drag the white cue ball, the red target ball, or the
 * crosshair (aim point). The guideline shows where the cue ball travels,
 * the ghost ball at contact, the target ball's path, and cushion bounces.
 */
class TableView(context: Context) : View(context) {

    var maxBounces = 2
        set(v) { field = v; invalidate() }

    // Positions stored as fractions of the table (0..1) so resizing keeps them.
    private val cueN = PointF(0.25f, 0.50f)
    private val targetN = PointF(0.60f, 0.42f)
    private val aimN = PointF(0.85f, 0.45f)

    private val dp = resources.displayMetrics.density
    private val outer = RectF()   // wooden rail
    private val table = RectF()   // felt (playing surface)
    private var r = 10f           // ball radius in px

    private var dragging: PointF? = null

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; this.style = style; strokeWidth = width
        }

    private val railPaint = paint(Color.rgb(92, 54, 28))
    private val feltPaint = paint(Color.rgb(20, 110, 60))
    private val pocketPaint = paint(Color.rgb(15, 15, 15))
    private val cuePaint = paint(Color.WHITE)
    private val targetPaint = paint(Color.rgb(200, 30, 30))
    private val outlinePaint = paint(Color.BLACK, Paint.Style.STROKE, 2f * dp)
    private val cuePathPaint = paint(Color.WHITE, Paint.Style.STROKE, 2f * dp)
    private val targetPathPaint = paint(Color.YELLOW, Paint.Style.STROKE, 2f * dp)
    private val tangentPaint = paint(Color.CYAN, Paint.Style.STROKE, 2f * dp)
    private val ghostPaint = paint(Color.WHITE, Paint.Style.STROKE, 2f * dp).apply { alpha = 170 }
    private val aimPaint = paint(Color.rgb(255, 170, 0), Paint.Style.STROKE, 2.5f * dp)
    private val textPaint = paint(Color.WHITE).apply { textSize = 16f * dp }

    fun reset() {
        cueN.set(0.25f, 0.50f); targetN.set(0.60f, 0.42f); aimN.set(0.85f, 0.45f)
        invalidate()
    }

    // ---------- layout ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = 12f * dp
        // Outer height = 0.54 * outer width when the rail is 4% of width.
        val ow = min(w - 2 * m, (h - 2 * m) / 0.54f)
        val rw = 0.04f * ow
        val tw = ow - 2 * rw
        val th = tw / 2f
        val oh = th + 2 * rw
        val ol = (w - ow) / 2f
        val ot = (h - oh) / 2f
        outer.set(ol, ot, ol + ow, ot + oh)
        table.set(ol + rw, ot + rw, ol + rw + tw, ot + rw + th)
        r = tw * 0.02f
    }

    private fun toPx(n: PointF) =
        PointF(table.left + n.x * table.width(), table.top + n.y * table.height())

    private fun setFromPx(n: PointF, x: Float, y: Float) {
        val cx = x.coerceIn(table.left + r, table.right - r)
        val cy = y.coerceIn(table.top + r, table.bottom - r)
        n.set((cx - table.left) / table.width(), (cy - table.top) / table.height())
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handles = listOf(cueN, targetN, aimN)
                val grab = 48f * dp
                dragging = handles
                    .map { it to hypot(toPx(it).x - e.x, toPx(it).y - e.y) }
                    .filter { it.second < grab }
                    .minByOrNull { it.second }?.first
                    ?: aimN   // tap on empty felt moves the aim point
                setFromPx(dragging!!, e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragging?.let { setFromPx(it, e.x, e.y); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }

    // ---------- geometry ----------

    private class Trace(val pts: List<PointF>, val ghost: PointF?)

    private fun norm(x: Float, y: Float): PointF {
        val l = hypot(x, y)
        return if (l < 1e-6f) PointF(0f, 0f) else PointF(x / l, y / l)
    }

    /** Time t>0 when a ball of centre p moving along d first touches `other` (distance = dist). */
    private fun hitTime(p: PointF, d: PointF, other: PointF, dist: Float): Float? {
        val fx = p.x - other.x
        val fy = p.y - other.y
        val b = fx * d.x + fy * d.y
        val c = fx * fx + fy * fy - dist * dist
        if (c < 0f) return null                 // already overlapping
        val disc = b * b - c
        if (disc < 0f) return null
        val t = -b - sqrt(disc)
        return if (t > 1e-3f) t else null
    }

    /**
     * Follows a ball centre from `start` along `dirIn`, reflecting off the cushions
     * up to `bounces` times. Stops early at the ghost position if it touches `ball`.
     */
    private fun trace(start: PointF, dirIn: PointF, bounces: Int, ball: PointF?): Trace {
        val minX = table.left + r
        val maxX = table.right - r
        val minY = table.top + r
        val maxY = table.bottom - r

        var p = PointF(start.x, start.y)
        var d = PointF(dirIn.x, dirIn.y)
        val pts = arrayListOf(PointF(p.x, p.y))
        var n = 0
        while (true) {
            val tx = when {
                d.x > 1e-6f -> (maxX - p.x) / d.x
                d.x < -1e-6f -> (minX - p.x) / d.x
                else -> Float.MAX_VALUE
            }
            val ty = when {
                d.y > 1e-6f -> (maxY - p.y) / d.y
                d.y < -1e-6f -> (minY - p.y) / d.y
                else -> Float.MAX_VALUE
            }
            val tWall = max(0f, min(tx, ty))
            val tBall = ball?.let { hitTime(p, d, it, 2f * r) }

            if (tBall != null && tBall < tWall) {
                val g = PointF(p.x + d.x * tBall, p.y + d.y * tBall)
                pts.add(g)
                return Trace(pts, g)
            }

            val hit = PointF(p.x + d.x * tWall, p.y + d.y * tWall)
            pts.add(hit)
            if (n >= bounces) return Trace(pts, null)

            d = if (tx <= ty) PointF(-d.x, d.y) else PointF(d.x, -d.y)
            p = hit
            n++
        }
    }

    // ---------- drawing ----------

    private fun drawPath(c: Canvas, pts: List<PointF>, p: Paint) {
        for (i in 0 until pts.size - 1) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, p)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(25, 25, 30))
        c.drawRoundRect(outer, 14f * dp, 14f * dp, railPaint)
        c.drawRect(table, feltPaint)

        // Pockets: 4 corners + 2 middles.
        val pr = r * 1.7f
        val xs = floatArrayOf(table.left, table.centerX(), table.right)
        for (x in xs) for (y in floatArrayOf(table.top, table.bottom)) c.drawCircle(x, y, pr, pocketPaint)

        val cue = toPx(cueN)
        val tgt = toPx(targetN)
        val aim = toPx(aimN)

        val d = norm(aim.x - cue.x, aim.y - cue.y)
        var info = "Drag the balls or the crosshair"

        if (hypot(aim.x - cue.x, aim.y - cue.y) > 5f * dp) {
            val t = trace(cue, d, maxBounces, tgt)
            drawPath(c, t.pts, cuePathPaint)

            val g = t.ghost
            if (g != null) {
                c.drawCircle(g.x, g.y, r, ghostPaint)

                val n = norm(tgt.x - g.x, tgt.y - g.y)          // target ball direction
                drawPath(c, trace(tgt, n, maxBounces, null).pts, targetPathPaint)

                // Cue ball after contact: tangent line (the "90 degree rule" for a rolling ball).
                val dot = d.x * n.x + d.y * n.y
                val v = norm(d.x - n.x * dot, d.y - n.y * dot)
                if (v.x != 0f || v.y != 0f) {
                    c.drawLine(g.x, g.y, g.x + v.x * r * 7f, g.y + v.y * r * 7f, tangentPaint)
                }
                val cut = Math.toDegrees(acos(dot.coerceIn(-1f, 1f).toDouble()))
                info = "Cut angle: %.1f\u00B0".format(cut)
            } else {
                info = "No contact with target"
            }
        }

        // Balls and aim handle on top.
        c.drawCircle(tgt.x, tgt.y, r, targetPaint); c.drawCircle(tgt.x, tgt.y, r, outlinePaint)
        c.drawCircle(cue.x, cue.y, r, cuePaint); c.drawCircle(cue.x, cue.y, r, outlinePaint)
        val a = 12f * dp
        c.drawCircle(aim.x, aim.y, a, aimPaint)
        c.drawLine(aim.x - a * 1.5f, aim.y, aim.x + a * 1.5f, aim.y, aimPaint)
        c.drawLine(aim.x, aim.y - a * 1.5f, aim.x, aim.y + a * 1.5f, aimPaint)

        c.drawText(info, 16f * dp, 28f * dp, textPaint)
    }
}
