package dev.lolokeksu.scrimroom.model

/** Pure validation only; a passing request is NOT proof of account ownership. */
fun validateProfile(profile: PlayerProfile): String? = when {
    profile.riotId.length !in 5..64 || !profile.riotId.contains('#') ->
        "Укажи Riot ID вместе с тегом после #."
    profile.region.isBlank() -> "Укажи регион игрового аккаунта."
    !profile.eligible -> "Для заявки нужен Грандмастер+ в текущем или прошлом сезоне."
    profile.primary == profile.secondary -> "Основная и вторая роли должны отличаться."
    profile.screenshots.distinct().size < 2 -> "Нужно минимум два скриншота: статистика и чемпионы."
    else -> null
}
