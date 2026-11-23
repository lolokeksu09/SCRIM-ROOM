package dev.lolokeksu.scrimroom.tests

import dev.lolokeksu.scrimroom.match.MatchEngine
import dev.lolokeksu.scrimroom.model.QueuePlayer
import dev.lolokeksu.scrimroom.model.Rank
import dev.lolokeksu.scrimroom.model.Role
import dev.lolokeksu.scrimroom.model.PlayerProfile
import dev.lolokeksu.scrimroom.model.exampleQueue
import dev.lolokeksu.scrimroom.model.validateProfile

private var passed = 0
private fun verify(description: String, condition: Boolean) {
    check(condition) { description }
    passed++
    println("PASS: $description")
}

private inline fun expectFailure(description: String, block: () -> Unit) {
    val failed = runCatching(block).isFailure
    verify(description, failed)
}

fun main() {
    val draft = MatchEngine.build(exampleQueue())
    val assigned = draft.blue + draft.red
    verify("10 exactly assigned", assigned.size == 10)
    verify("unique players", assigned.map { it.player.id }.distinct().size == 10)
    verify("one player per role per team", draft.blue.map { it.role }.toSet() == Role.entries.toSet() && draft.red.map { it.role }.toSet() == Role.entries.toSet())
    verify("respect player role preferences", assigned.all { it.role == it.player.primary || it.role == it.player.secondary })
    verify("secondary role counter", draft.secondaryCount == assigned.count { it.onSecondary })
    verify("positive average score", draft.blueAverage > 0 && draft.redAverage > 0)
    val again = MatchEngine.build(exampleQueue())
    verify("deterministic draft", draft.blue.map { it.player.id } == again.blue.map { it.player.id } && draft.red.map { it.player.id } == again.red.map { it.player.id })
    expectFailure("less than 10 rejected") { MatchEngine.build(exampleQueue().take(9)) }
    expectFailure("duplicate IDs rejected") {
        MatchEngine.build(exampleQueue().toMutableList().apply { this[1] = this[0] })
    }
    expectFailure("invalid player rating rejected") {
        MatchEngine.build(exampleQueue().mapIndexed { i, p -> if (i == 0) p.copy(rating = 0) else p })
    }
    expectFailure("missing roles rejected") {
        MatchEngine.build(exampleQueue().map { it.copy(primary = Role.BARON, secondary = Role.MID) })
    }
    verify("GM qualifies", PlayerProfile(currentRank = Rank.GRANDMASTER).eligible)
    verify("previous season sovereign qualifies", PlayerProfile(currentRank = Rank.MASTER, previousRank = Rank.SOVEREIGN).eligible)
    verify("master does not qualify", !PlayerProfile(currentRank = Rank.MASTER, previousRank = Rank.MASTER).eligible)
    val validProfile = PlayerProfile(
        riotId = "Player#1234", currentRank = Rank.GRANDMASTER,
        screenshots = listOf("content://one", "content://two")
    )
    verify("valid claim accepted as application", validateProfile(validProfile) == null)
    verify("wrong rank rejected", validateProfile(validProfile.copy(currentRank = Rank.MASTER, previousRank = Rank.MASTER)) != null)
    verify("duplicate screenshots rejected", validateProfile(validProfile.copy(screenshots = listOf("content://one", "content://one"))) != null)
    verify("same roles rejected", validateProfile(validProfile.copy(secondary = validProfile.primary)) != null)
    verify("missing Riot tag rejected", validateProfile(validProfile.copy(riotId = "Player")) != null)
    println("PASS: $passed checks")
}
