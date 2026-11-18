package com.eried.eucplanet.nav.offline.map

/**
 * A decoded Mapbox vector tile: layers of features with their tags and
 * geometry in tile units (0 to [Layer.extent]).
 */
class VectorTile(val layers: Map<String, Layer>) {

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    /** [rings] are lines for a line, rings for a polygon (outer, then holes),
     *  single points for points. Coordinates are x, y pairs. */
    class Feature(val type: Int, val tags: Map<String, Any>, val rings: List<IntArray>) {
        fun str(key: String): String? = tags[key] as? String
        fun num(key: String): Double? = (tags[key] as? Number)?.toDouble()
        fun bool(key: String): Boolean = tags[key] == true
    }

    companion object {
        const val POINT = 1
        const val LINE = 2
        const val POLYGON = 3

        fun decode(b: ByteArray): VectorTile {
            val out = HashMap<String, Layer>()
            val r = Pb(b, 0, b.size)
            while (r.more()) {
                val (f, t) = r.key()
                if (f == 3 && t == 2) layer(r.sub())?.let { out[it.name] = it } else r.skip(t)
            }
            return VectorTile(out)
        }

        private fun layer(r: Pb): Layer? {
            var name = ""
            var extent = 4096
            val keys = ArrayList<String>()
            val values = ArrayList<Any>()
            val raw = ArrayList<Pb>()
            while (r.more()) {
                val (f, t) = r.key()
                when {
                    f == 1 && t == 2 -> name = r.string()
                    f == 2 && t == 2 -> raw += r.sub()
                    f == 3 && t == 2 -> keys += r.string()
                    f == 4 && t == 2 -> values += value(r.sub())
                    f == 5 && t == 0 -> extent = r.varint().toInt()
                    else -> r.skip(t)
                }
            }
            if (name.isEmpty()) return null
            return Layer(name, extent, raw.map { feature(it, keys, values) })
        }

        private fun value(r: Pb): Any {
            var v: Any = ""
            while (r.more()) {
                val (f, t) = r.key()
                v = when (f) {
                    1 -> r.string()
                    2 -> java.lang.Float.intBitsToFloat(r.fixed32())
                    3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                    4, 5 -> r.varint()
                    6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                    7 -> r.varint() != 0L
                    else -> { r.skip(t); v }
                }
            }
            return v
        }

        private fun feature(r: Pb, keys: List<String>, values: List<Any>): Feature {
            var type = 0
            val tags = HashMap<String, Any>()
            var geom = IntArray(0)
            while (r.more()) {
                val (f, t) = r.key()
                when {
                    f == 2 && t == 2 -> {
                        val p = r.sub()
                        while (p.more()) {
                            val k = p.varint().toInt()
                            val v = p.varint().toInt()
                            if (k < keys.size && v < values.size) tags[keys[k]] = values[v]
                        }
                    }
                    f == 3 && t == 0 -> type = r.varint().toInt()
                    f == 4 && t == 2 -> {
                        val p = r.sub()
                        val list = ArrayList<Int>()
                        while (p.more()) list += p.varint().toInt()
                        geom = list.toIntArray()
                    }
                    else -> r.skip(t)
                }
            }
            return Feature(type, tags, rings(geom))
        }

        /** Turns the move/line/close commands into coordinate lists. */
        private fun rings(g: IntArray): List<IntArray> {
            val out = ArrayList<IntArray>()
            var cur = ArrayList<Int>()
            var x = 0
            var y = 0
            var i = 0
            fun zz(n: Int) = (n ushr 1) xor -(n and 1)
            while (i < g.size) {
                val cmd = g[i] and 7
                val count = g[i] ushr 3
                i++
                when (cmd) {
                    1 -> repeat(count) {
                        if (cur.isNotEmpty()) out += cur.toIntArray()
                        cur = ArrayList()
                        x += zz(g[i++]); y += zz(g[i++])
                        cur += x; cur += y
                    }
                    2 -> repeat(count) {
                        x += zz(g[i++]); y += zz(g[i++])
                        cur += x; cur += y
                    }
                    7 -> if (cur.size >= 2) { cur += cur[0]; cur += cur[1] }
                }
            }
            if (cur.isNotEmpty()) out += cur.toIntArray()
            return out
        }
    }

    /** Minimal protobuf reader. */
    private class Pb(val b: ByteArray, var i: Int, val end: Int) {
        fun more() = i < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val c = b[i++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                s += 7
                if (c < 0x80) return r
            }
        }
        fun key(): Pair<Int, Int> = varint().toInt().let { (it ushr 3) to (it and 7) }
        fun sub(): Pb { val n = varint().toInt(); val p = Pb(b, i, i + n); i += n; return p }
        fun string(): String { val n = varint().toInt(); val s = String(b, i, n, Charsets.UTF_8); i += n; return s }
        fun fixed32(): Int { var v = 0; for (k in 0 until 4) v = v or ((b[i + k].toInt() and 0xFF) shl (8 * k)); i += 4; return v }
        fun fixed64(): Long { var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; return v }
        fun skip(t: Int) {
            when (t) {
                0 -> varint()
                1 -> i += 8
                2 -> i += varint().toInt()
                5 -> i += 4
            }
        }
    }
}
