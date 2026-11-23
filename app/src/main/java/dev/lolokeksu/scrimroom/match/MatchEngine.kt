package dev.lolokeksu.scrimroom.match

import dev.lolokeksu.scrimroom.model.AssignedPlayer
import dev.lolokeksu.scrimroom.model.MatchDraft
import dev.lolokeksu.scrimroom.model.QueuePlayer
import dev.lolokeksu.scrimroom.model.Role
import kotlin.math.abs

/**
 * Local-only adaptation of SCRIM ROOM MVP 0.1 matching rules.
 * Searches a bounded pool of 14 participants for ten unique players,
 * two matching role assignments per role, and then splits mirrored teams.
 * Ratings are organizer-supplied estimates, not Riot data.
 */
object MatchEngine {
    private const val MAX_POOL = 14
    private const val MAX_VISITS = 100_000

    fun build(players: List<QueuePlayer>): MatchDraft {
        require(players.map { it.id }.distinct().size == players.size) { "Обнаружены повторяющиеся ID игроков." }
        require(players.size >= 10) { "Для матча требуется минимум 10 игроков." }
        require(players.all { it.rating in 500..3500 }) { "Недопустимая оценка игрока." }
        val pool = players.take(MAX_POOL)
        val roles = Role.entries.toList()
        val candidates = roles.associateWith { role ->
            pool.indices.filter { i -> pool[i].primary == role || pool[i].secondary == role }
        }
        val missing = roles.filter { (candidates[it]?.size ?: 0) < 2 }
        require(missing.isEmpty()) { "Для ролей ${missing.joinToString { it.title }} нужно минимум два игрока." }
        val order = roles.sortedBy { candidates.getValue(it).size }
        val picked = mutableMapOf<Role, Pair<Int, Int>>()
        var bestScore = Double.POSITIVE_INFINITY
        var best: MatchDraft? = null
        var visits = 0
        var truncated = false

        fun evaluate(secondaries: Int, recency: Int) {
            // 16 mirrored combinations suffice: global blue/red swap is equivalent.
            for (mask in 0 until 16) {
                val blue = ArrayList<AssignedPlayer>(5)
                val red = ArrayList<AssignedPlayer>(5)
                var blueSum = 0
                var redSum = 0
                var gap = 0
                roles.forEachIndexed { roleIndex, role ->
                    val (a, b) = picked.getValue(role)
                    val left = pool[if ((mask and (1 shl roleIndex)) != 0) b else a]
                    val right = pool[if ((mask and (1 shl roleIndex)) != 0) a else b]
                    blue += AssignedPlayer(left, role)
                    red += AssignedPlayer(right, role)
                    blueSum += left.rating
                    redSum += right.rating
                    gap += abs(left.rating - right.rating)
                }
                val averageGap = abs(blueSum - redSum) / 5.0
                val score = 0.8 * averageGap + 0.22 * gap + 75 * secondaries + 5 * recency
                if (score < bestScore) {
                    bestScore = score
                    best = MatchDraft(
                        blue, red, (blueSum / 5.0).toInt(), (redSum / 5.0).toInt(),
                        gap, secondaries, false
                    )
                }
            }
        }

        fun search(depth: Int, used: Int, secondaries: Int, recency: Int) {
            if (visits++ >= MAX_VISITS) { truncated = true; return }
            if (depth == order.size) { evaluate(secondaries, recency); return }
            val role = order[depth]
            val options = candidates.getValue(role)
            for (i in 0 until options.size - 1) {
                val a = options[i]
                if ((used and (1 shl a)) != 0) continue
                for (j in (i + 1) until options.size) {
                    val b = options[j]
                    if ((used and (1 shl b)) != 0) continue
                    val extra = (if (pool[a].primary == role) 0 else 1) +
                        (if (pool[b].primary == role) 0 else 1)
                    val nextSecondaries = secondaries + extra
                    val nextRecency = recency + a + b
                    if (best != null && 75 * nextSecondaries + 5 * nextRecency >= bestScore) continue
                    picked[role] = a to b
                    search(depth + 1, used or (1 shl a) or (1 shl b), nextSecondaries, nextRecency)
                }
            }
            picked.remove(role)
        }
        search(0, 0, 0, 0)
        val result = best ?: error("Не удалось составить две команды по ролям. Измените вторые роли игроков.")
        return result.copy(truncated = truncated)
    }
}
