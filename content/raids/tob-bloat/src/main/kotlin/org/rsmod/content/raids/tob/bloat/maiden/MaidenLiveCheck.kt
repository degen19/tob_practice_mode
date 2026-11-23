package org.rsmod.content.raids.tob.bloat.maiden

/**
 * `java ... MaidenLiveCheckKt [raids] [debug seed]`: the sim's raids as the live room plays them ([MaidenMover]: the
 * bots run to their spots and an attack waits for them) against the sim's own (free movement) - success rate, room
 * times and the held attacks by kind. `debug <seed>` prints that raid's holds and both runs' leaks.
 */
fun main(args: Array<String>) {
    val n = args.getOrNull(0)?.toInt() ?: 300
    if (args.getOrNull(1) == "trace") {
        val seed = args.getOrNull(2) ?: "r1"
        val upTo = args.getOrNull(3)?.toInt() ?: 40
        val log = ArrayList<String>()
        val e = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng(seed), MaidenPlans.FIVE_TARGET, log = log)
        val m = MaidenMover(e)
        val rows = e.ps.map { StringBuilder() }
        while (!e.over && e.t < upTo) {
            m.move()
            val before = e.ps.map { it.next }
            val lines = e.ps.map { e.lineAt(it) }
            e.tick()
            m.afterTick()
            for (p in e.ps) {
                val xy = m.pos[p.i]
                val atk = if (p.next != before[p.i]) lines[p.i] else null
                rows[p.i].append("${e.t}:(${xy[0] - 2}, ${14 - xy[1]})").append(atk?.let { "[$it]" } ?: "").append(' ')
            }
        }
        for (p in e.ps) println("== ${p.name} (blert tiles: x from her west edge, y from her south edge)\n${rows[p.i]}")
        return
    }
    if (args.getOrNull(1) == "dist") {
        // how far out each kind of attack goes from, over the raids (to set against blert's)
        val by = java.util.TreeMap<String, java.util.TreeMap<Int, Int>>()
        for (i in 1..n) {
            val e = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng("r$i"), MaidenPlans.FIVE_TARGET)
            val m = MaidenMover(e)
            while (!e.over && e.t < MaidenMover.MAX_T) {
                m.move()
                val before = e.ps.map { it.next }
                val lines = e.ps.map { e.lineAt(it) }
                e.tick()
                for (p in e.ps) {
                    if (p.next == before[p.i]) continue
                    val a = lines[p.i]
                    val (box) = e.reach(p, a) ?: continue
                    val xy = m.pos[p.i]
                    val d = maxOf(maxOf(box[0] - xy[0], xy[0] - box[2], 0), maxOf(box[1] - xy[1], xy[1] - box[3], 0))
                    val k = a.substringBefore('|').substringBefore(':') + if (MaidenEngine.crabOf(a) != null) "*" else ""
                    by.getOrPut(k) { java.util.TreeMap() }.merge(d, 1, Int::plus)
                }
                m.afterTick()
            }
        }
        for ((k, v) in by) println("$k ${v.values.sum()} $v")
        return
    }
    if (args.getOrNull(1) == "debug") {
        val seed = args.getOrNull(2) ?: "r1"
        val logs = listOf(ArrayList<String>(), ArrayList())
        val sim = MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng(seed), MaidenPlans.FIVE_TARGET, log = logs[0])
        while (!sim.over && sim.t < MaidenMover.MAX_T) sim.tick()
        MaidenMover.play(seed, logs[1]) { e, p, a, xy, g -> println("t${e.t} hold ${p.name} $a at ${xy.joinToString(",")} goal ${g.joinToString(",")}") }
        val rx = Regex(System.getenv("RX") ?: "leaks|dead|proc")
        for ((i, l) in logs.withIndex()) {
            println(if (i == 0) "== sim" else "== live")
            l.filter { rx.containsMatchIn(it) }.forEach(::println)
        }
        return
    }
    val holds = HashMap<String, Int>()
    fun kind(e: MaidenEngine, a: String): String {
        val k = a.substringBefore('|').substringBefore('@').let { if (it.startsWith("sc") && ':' in it) it.substringBefore(':') else it }
        return "$k ${if (e.proc == null) "pre" else if (e.proc50 == null) "70" else if (e.proc30 == null) "50" else "30"}"
    }
    val sims = (1..n).map { i -> MaidenEngine(5, MaidenPlans.FIVE_STACK, SimRng("r$i"), MaidenPlans.FIVE_TARGET).also { while (!it.over && it.t < MaidenMover.MAX_T) it.tick() } }
    val lives = (1..n).map { i -> MaidenMover.play("r$i") { e, _, a, _, _ -> holds.merge(kind(e, a), 1, Int::plus) } }
    if (System.getenv("LOST") != null) for (i in 0 until n) if (sims[i].leaks < MaidenIndex.FAIL_LEAKS && lives[i].leaks >= MaidenIndex.FAIL_LEAKS) println("lost r${i + 1}: leaks ${lives[i].leaks}")
    fun report(name: String, r: List<MaidenEngine>) {
        val wins = r.filter { it.leaks < MaidenIndex.FAIL_LEAKS }.map { it.dead!! + MaidenEngine.DEATH_TICKS }.sorted()
        println("$name: ${100 * wins.size / n}% successful, median ${wins.getOrNull(wins.size / 2)?.let { MaidenIndex.fmt(it) }}, fastest ${wins.firstOrNull()?.let { MaidenIndex.fmt(it) }}")
    }
    report("sim ", sims)
    report("live", lives)
    println("held: " + holds.entries.sortedByDescending { it.value }.take(20).joinToString("  ") { "${it.key}=${it.value}" })
    if (System.getenv("DIFF") != null) {
        for (i in 0 until minOf(n, 60)) {
            val a = sims[i]
            val b = lives[i]
            if ((a.leaks < MaidenIndex.FAIL_LEAKS) != (b.leaks < MaidenIndex.FAIL_LEAKS)) println("r${i + 1} sim leaks ${a.leaks} dead ${a.dead}  live leaks ${b.leaks} dead ${b.dead}")
        }
    }
}
