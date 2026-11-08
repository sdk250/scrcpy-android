package dev.scrcpyandroid.scrcpy

import kotlin.math.floor

/** Content rectangle inside the view, in view pixels. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Fits a source of [srcW]x[srcH] into [dstW]x[dstH] keeping its aspect ratio, centred.
 * One side always touches the destination edge; the rest is letterbox.
 * Returns an empty rect when any dimension is non-positive.
 */
fun fitCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int): FitRect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return FitRect(0f, 0f, 0f, 0f)
    val w: Float
    val h: Float
    if (dstW.toLong() * srcH <= dstH.toLong() * srcW) {
        // Width-limited: the picture touches left and right edges.
        w = dstW.toFloat()
        h = dstW.toFloat() * srcH / srcW
    } else {
        // Height-limited: the picture touches top and bottom edges.
        h = dstH.toFloat()
        w = dstH.toFloat() * srcW / srcH
    }
    return FitRect((dstW - w) / 2f, (dstH - h) / 2f, w, h)
}

/**
 * Maps view coordinates to remote device pixels. Reads the latest [videoW]/[videoH]
 * on every call, so a rotation (new session packet) takes effect immediately.
 */
class TouchMapper {
    @Volatile var videoW = 0
    @Volatile var videoH = 0
    var viewW = 0
    var viewH = 0

    /** Device pixel for a point inside the picture, or null on black bars or when there is no video. */
    fun map(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val vw = videoW
        val vh = videoH
        val r = contentRect(vw, vh) ?: return null
        if (viewX < r.left || viewX >= r.left + r.width) return null
        if (viewY < r.top || viewY >= r.top + r.height) return null
        return toDevice(viewX, viewY, r, vw, vh)
    }

    /** Like [map], but a point on the black bars is clamped to the nearest picture edge. */
    fun clampToContent(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val vw = videoW
        val vh = videoH
        val r = contentRect(vw, vh) ?: return null
        val cx = viewX.coerceIn(r.left, r.left + r.width)
        val cy = viewY.coerceIn(r.top, r.top + r.height)
        return toDevice(cx, cy, r, vw, vh)
    }

    private fun contentRect(vw: Int, vh: Int): FitRect? {
        if (vw <= 0 || vh <= 0) return null
        val r = fitCenter(vw, vh, viewW, viewH)
        return if (r.width > 0f && r.height > 0f) r else null
    }

    private fun toDevice(x: Float, y: Float, r: FitRect, vw: Int, vh: Int): Pair<Int, Int> {
        val dx = floor((x - r.left) * vw / r.width).toInt().coerceIn(0, vw - 1)
        val dy = floor((y - r.top) * vh / r.height).toInt().coerceIn(0, vh - 1)
        return dx to dy
    }
}
