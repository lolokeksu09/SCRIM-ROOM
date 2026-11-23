package dev.lolokeksu.scrimroom.model

enum class Role(val title: String) {
    BARON("Барон"), JUNGLE("Лес"), MID("Мид"), DRAGON("ADC"), SUPPORT("Поддержка")
}

enum class Rank(val title: String, val level: Int) {
    BELOW_MASTER("Алмаз и ниже", 0),
    MASTER("Мастер", 1),
    GRANDMASTER("Грандмастер", 2),
    CHALLENGER("Претендент", 3),
    SOVEREIGN("Суверен", 4)
}

enum class Verification { DRAFT, PENDING_REVIEW }

data class PlayerProfile(
    val riotId: String = "",
    val region: String = "EUROPE",
    val currentRank: Rank = Rank.MASTER,
    val previousRank: Rank = Rank.MASTER,
    val primary: Role = Role.JUNGLE,
    val secondary: Role = Role.MID,
    val screenshots: List<String> = emptyList(),
    val verification: Verification = Verification.DRAFT
) {
    val eligible: Boolean get() = currentRank.level >= Rank.GRANDMASTER.level || previousRank.level >= Rank.GRANDMASTER.level
}

data class QueuePlayer(
    val id: String,
    val nickname: String,
    val rating: Int,
    val primary: Role,
    val secondary: Role
)

data class AssignedPlayer(val player: QueuePlayer, val role: Role) {
    val onSecondary: Boolean get() = player.primary != role
}

data class MatchDraft(
    val blue: List<AssignedPlayer>,
    val red: List<AssignedPlayer>,
    val blueAverage: Int,
    val redAverage: Int,
    val totalRoleGap: Int,
    val secondaryCount: Int,
    val truncated: Boolean
) {
    val teamAverageGap: Int get() = kotlin.math.abs(blueAverage - redAverage)
}

enum class Side { BLUE, RED }

data class DemoResult(val id: Long, val winner: Side, val blueAverage: Int, val redAverage: Int)

/** Fictional test entries only; never treat these ratings as Riot MMR. */
fun exampleQueue(): List<QueuePlayer> = listOf(
    QueuePlayer("01", "DemoBaronA", 2090, Role.BARON, Role.MID),
    QueuePlayer("02", "DemoBaronB", 2020, Role.BARON, Role.SUPPORT),
    QueuePlayer("03", "DemoJungleA", 2180, Role.JUNGLE, Role.BARON),
    QueuePlayer("04", "DemoJungleB", 2130, Role.JUNGLE, Role.MID),
    QueuePlayer("05", "DemoMidA", 2070, Role.MID, Role.JUNGLE),
    QueuePlayer("06", "DemoMidB", 2110, Role.MID, Role.BARON),
    QueuePlayer("07", "DemoAdcA", 1990, Role.DRAGON, Role.MID),
    QueuePlayer("08", "DemoAdcB", 2050, Role.DRAGON, Role.SUPPORT),
    QueuePlayer("09", "DemoSupportA", 2010, Role.SUPPORT, Role.DRAGON),
    QueuePlayer("10", "DemoSupportB", 1970, Role.SUPPORT, Role.BARON),
    QueuePlayer("11", "DemoFlexA", 2060, Role.JUNGLE, Role.SUPPORT),
    QueuePlayer("12", "DemoFlexB", 2030, Role.DRAGON, Role.BARON)
)
