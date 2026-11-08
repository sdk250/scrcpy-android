package dev.scrcpyandroid.ui

import dev.scrcpyandroid.scrcpy.ControlMessages
import dev.scrcpyandroid.scrcpy.TouchMapper

/**
 * Turns Android touch events into scrcpy inject-touch messages (pure logic, no Android types).
 *
 * Policy: a pointer whose DOWN lands on the black bars (or before any video) is ignored for its
 * whole gesture; a pointer that went down inside the picture keeps being reported, clamped to the
 * picture edge when it wanders onto the bars. screenW/H are always [TouchMapper.videoW]/[TouchMapper.videoH]
 * at send time (the server's current session size).
 *
 * Not thread-safe: call from the UI thread only.
 */
class TouchRouter(private val mapper: TouchMapper) {
    /** One pointer of a MotionEvent: id from getPointerId, view coordinates and raw pressure. */
    data class Pointer(val id: Int, val x: Float, val y: Float, val pressure: Float)

    /** Pointers whose gesture started inside the picture. */
    private val active = LinkedHashSet<Int>()

    /**
     * [action] is MotionEvent.getActionMasked(), [actionIndex] getActionIndex() (only meaningful for
     * DOWN/UP actions), [pointers] every pointer of the event in index order.
     */
    fun onEvent(action: Int, actionIndex: Int, pointers: List<Pointer>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        when (action) {
            ACTION_DOWN, ACTION_POINTER_DOWN -> {
                if (action == ACTION_DOWN) active.clear()
                val p = pointers.getOrNull(actionIndex) ?: return out
                val pos = mapper.map(p.x, p.y) ?: return out
                active += p.id
                out += message(AMOTION_DOWN, p, pos)
            }
            ACTION_MOVE -> for (p in pointers) {
                if (p.id !in active) continue
                val pos = mapper.clampToContent(p.x, p.y) ?: continue
                out += message(AMOTION_MOVE, p, pos)
            }
            ACTION_UP, ACTION_POINTER_UP -> {
                val p = pointers.getOrNull(actionIndex) ?: return out
                if (!active.remove(p.id)) return out
                val pos = mapper.clampToContent(p.x, p.y) ?: return out
                out += message(AMOTION_UP, p, pos)
            }
            ACTION_CANCEL -> {
                for (p in pointers) {
                    if (p.id !in active) continue
                    val pos = mapper.clampToContent(p.x, p.y) ?: continue
                    out += message(AMOTION_UP, p, pos)
                }
                active.clear()
            }
        }
        return out
    }

    private fun message(action: Int, p: Pointer, pos: Pair<Int, Int>): ByteArray =
        ControlMessages.touch(
            action = action,
            pointerId = p.id.toLong(),
            x = pos.first,
            y = pos.second,
            screenW = mapper.videoW,
            screenH = mapper.videoH,
            pressure = p.pressure.coerceIn(0f, 1f),
        )

    companion object {
        // Same values as android.view.MotionEvent.ACTION_*.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6

        // scrcpy (AMOTION_EVENT_ACTION_*) actions sent to the server.
        private const val AMOTION_DOWN = 0
        private const val AMOTION_UP = 1
        private const val AMOTION_MOVE = 2
    }
}
