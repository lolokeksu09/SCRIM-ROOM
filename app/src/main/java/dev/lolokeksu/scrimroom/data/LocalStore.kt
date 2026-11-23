package dev.lolokeksu.scrimroom.data

import android.content.Context
import dev.lolokeksu.scrimroom.model.DemoResult
import dev.lolokeksu.scrimroom.model.PlayerProfile
import dev.lolokeksu.scrimroom.model.Rank
import dev.lolokeksu.scrimroom.model.Role
import dev.lolokeksu.scrimroom.model.Side
import dev.lolokeksu.scrimroom.model.Verification
import org.json.JSONArray
import org.json.JSONObject

/** Local profile/demo history. Online session state is stored separately. */
class LocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scrim_room_v1", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> safeEnum(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    fun readProfile(): PlayerProfile {
        val raw = prefs.getString("profile", null) ?: return PlayerProfile()
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("screenshots") ?: JSONArray()
            val shots = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            PlayerProfile(
                riotId = obj.optString("riotId"),
                region = obj.optString("region", "EUROPE"),
                currentRank = safeEnum(obj.optString("currentRank"), Rank.MASTER),
                previousRank = safeEnum(obj.optString("previousRank"), Rank.MASTER),
                primary = safeEnum(obj.optString("primary"), Role.JUNGLE),
                secondary = safeEnum(obj.optString("secondary"), Role.MID),
                screenshots = shots,
                verification = safeEnum(obj.optString("verification"), Verification.DRAFT)
            )
        }.getOrElse { PlayerProfile() }
    }

    fun writeProfile(profile: PlayerProfile) {
        val obj = JSONObject().apply {
            put("riotId", profile.riotId)
            put("region", profile.region)
            put("currentRank", profile.currentRank.name)
            put("previousRank", profile.previousRank.name)
            put("primary", profile.primary.name)
            put("secondary", profile.secondary.name)
            put("screenshots", JSONArray(profile.screenshots))
            put("verification", profile.verification.name)
        }
        prefs.edit().putString("profile", obj.toString()).apply()
    }


    fun readServerUrl(): String = prefs.getString("server_url", "") ?: ""

    /** Switching server invalidates credentials to avoid accidentally sending them to another host. */
    fun writeServerUrl(newUrl: String) {
        if (newUrl != readServerUrl()) {
            prefs.edit().putString("server_url", newUrl).remove("server_token")
                .remove("submitted_fingerprint").remove("uploaded_uris").apply()
        }
    }

    fun readServerToken(): String = prefs.getString("server_token", "") ?: ""
    fun writeServerToken(value: String) { prefs.edit().putString("server_token", value).apply() }

    fun readSubmittedFingerprint(): String = prefs.getString("submitted_fingerprint", "") ?: ""
    fun writeSubmittedFingerprint(value: String) { prefs.edit().putString("submitted_fingerprint", value).apply() }

    fun readUploadedUris(): Set<String> = prefs.getStringSet("uploaded_uris", emptySet())?.toSet() ?: emptySet()
    fun writeUploadedUris(value: Set<String>) { prefs.edit().putStringSet("uploaded_uris", value.toSet()).apply() }

    fun readResults(): List<DemoResult> {
        val raw = prefs.getString("results", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    val obj = arr.getJSONObject(index)
                    DemoResult(
                        obj.getLong("id"),
                        safeEnum(obj.optString("winner"), Side.BLUE),
                        obj.getInt("blueAverage"),
                        obj.getInt("redAverage")
                    )
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    fun writeResults(results: List<DemoResult>) {
        val array = JSONArray()
        results.take(50).forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("winner", entry.winner.name)
                put("blueAverage", entry.blueAverage)
                put("redAverage", entry.redAverage)
            })
        }
        prefs.edit().putString("results", array.toString()).apply()
    }
}
