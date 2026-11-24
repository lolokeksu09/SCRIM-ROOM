package dev.lolokeksu.scrimroom.data

import android.content.ContentResolver
import android.net.Uri
import dev.lolokeksu.scrimroom.model.PlayerProfile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** HTTPS-only pilot server client. Never follows redirects with bearer credentials. */
class RemoteClient(baseInput: String) {
    private val base: String = baseInput.trim().trimEnd('/')

    init {
        val uri = URI(base)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
                && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
                && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
            "Нужен адрес HTTPS сервера, например https://example.org"
        }
    }

    private fun open(method: String, path: String, token: String?): HttpURLConnection {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun parse(connection: HttpURLConnection): JSONObject {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val payload = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val message = payload.optString("detail").takeIf { it.isNotBlank() }
                    ?: "Сервер вернул HTTP $code"
                throw RemoteException(code, message)
            }
            return payload
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonRequest(method: String, path: String, token: String? = null, data: JSONObject? = null): JSONObject {
        val connection = open(method, path, token)
        if (data != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
        }
        return parse(connection)
    }

    fun register(profile: PlayerProfile): JSONObject =
        jsonRequest("POST", "/v1/accounts", data = profilePayload(profile))

    fun updateProfile(token: String, profile: PlayerProfile): JSONObject =
        jsonRequest("PUT", "/v1/me", token, profilePayload(profile))

    fun account(token: String): JSONObject = jsonRequest("GET", "/v1/me", token)
    fun state(token: String): JSONObject = jsonRequest("GET", "/v1/queue/state", token)
    fun join(token: String, sessionId: String? = null): JSONObject =
        jsonRequest("POST", "/v1/queue/join" + (sessionId?.let { "?session_id=" + Uri.encode(it) } ?: ""), token)
    fun sessions(token: String): List<PlaySession> {
        val rows = jsonRequest("GET", "/v1/sessions", token).getJSONArray("sessions")
        return (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val counts = r.getJSONObject("roles")
            PlaySession(r.getString("id"), r.getString("title"), r.getString("region"),
                r.getLong("starts_at"), r.getLong("ends_at"), r.getInt("capacity"),
                r.getInt("registered"), r.getInt("waiting"), r.getString("status"),
                r.getBoolean("enrolled"), listOf("BARON","JUNGLE","MID","DRAGON","SUPPORT").associateWith { counts.optInt(it) })
        }
    }
    fun signup(token: String, id: String) = jsonRequest("POST", "/v1/sessions/${Uri.encode(id)}/signup", token)
    fun withdraw(token: String, id: String) = jsonRequest("POST", "/v1/sessions/${Uri.encode(id)}/withdraw", token)
    fun leave(token: String): JSONObject = jsonRequest("POST", "/v1/queue/leave", token)
    fun ready(token: String, matchId: String): JSONObject = jsonRequest("POST", "/v1/matches/$matchId/ready", token)
    fun decline(token: String, matchId: String): JSONObject = jsonRequest("POST", "/v1/matches/$matchId/decline", token)

    fun reportResult(token: String, matchId: String, winner: String): JSONObject =
        jsonRequest("POST", "/v1/matches/$matchId/result", token, JSONObject().put("winner", winner))
    fun results(token: String): List<OnlineResult> {
        val rows = jsonRequest("GET", "/v1/me/results", token).getJSONArray("results")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            OnlineResult(row.getString("match_id"), row.getString("winner"), row.getString("side"),
                row.getLong("resolved_at"))
        }
    }

    fun recognizeEvidence(token: String, slot: Int): OcrSuggestions {
        val obj = jsonRequest("POST", "/v1/me/evidence/$slot/ocr", token).getJSONObject("suggestions")
        val ranks = obj.getJSONArray("rank_candidates")
        return OcrSuggestions(slot, if (obj.isNull("matches")) null else obj.getInt("matches"),
            if (obj.isNull("win_rate")) null else obj.getDouble("win_rate"),
            if (obj.isNull("kda")) null else obj.getDouble("kda"),
            (0 until ranks.length()).map { ranks.getString(it) })
    }

    private fun JSONObject.socialPlayer() = SocialPlayer(getString("id"), getString("riot_id"), getString("primary"), getString("secondary"), getString("relationship"))
    fun findPlayer(token: String, riotId: String) = jsonRequest("GET", "/v1/community/find?riot_id=" + Uri.encode(riotId), token).socialPlayer()
    fun community(token: String): CommunityData {
        val data = jsonRequest("GET", "/v1/community", token)
        fun rows(name: String) = data.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val recent = jsonRequest("GET", "/v1/community/recent-players", token).getJSONArray("players")
        return CommunityData(rows("friends").map { it.socialPlayer() },
            rows("invites").map { SessionInvitation(it.getString("id"), it.getString("title"), it.getString("sender"), it.getLong("starts_at")) },
            rows("reports").map { ComplaintStatus(it.getString("category"), it.getString("status")) },
            (0 until recent.length()).map { i -> recent.getJSONObject(i).let {
                RecentPlayer(it.getString("match_id"), it.getString("account_id"), it.getString("riot_id"), it.getLong("created_at"))
            } }, data.getInt("confirmed_complaints"))
    }
    fun requestFriend(token: String, id: String) = jsonRequest("POST", "/v1/community/friends/request", token, JSONObject().put("account_id", id))
    fun friendAction(token: String, id: String, accept: Boolean) = jsonRequest("POST", "/v1/community/friends/${Uri.encode(id)}/${if (accept) "accept" else "remove"}", token)
    fun inviteFriend(token: String, id: String, sessionId: String) = jsonRequest("POST", "/v1/community/invites", token, JSONObject().put("account_id", id).put("session_id", sessionId))
    fun dismissInvite(token: String, id: String) = jsonRequest("POST", "/v1/community/invites/${Uri.encode(id)}/dismiss", token)
    fun complain(token: String, matchId: String, id: String, category: String, note: String) = jsonRequest("POST", "/v1/community/reports", token,
        JSONObject().put("match_id", matchId).put("account_id", id).put("category", category).put("note", note))

    fun uploadEvidence(token: String, resolver: ContentResolver, uri: Uri): JSONObject {
        val mime = resolver.getType(uri)
        require(mime in setOf("image/png", "image/jpeg", "image/webp")) {
            "Поддерживаются скриншоты PNG, JPEG и WEBP"
        }
        val content = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (buffer.size() + count > 4 * 1024 * 1024) error("Скриншот превышает 4 МБ")
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        } ?: error("Не удалось открыть скриншот")
        val boundary = "ScrimUpload" + UUID.randomUUID().toString().replace("-", "")
        val connection = open("POST", "/v1/me/evidence", token)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"image\"; filename=\"evidence\"\r\n".toByteArray())
            output.write("Content-Type: $mime\r\n\r\n".toByteArray())
            output.write(content)
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return parse(connection)
    }

    private fun profilePayload(profile: PlayerProfile) = JSONObject().apply {
        put("riot_id", profile.riotId.trim())
        put("region", when (profile.region.trim().uppercase()) {
            "ЕВРОПА", "EU", "EUW", "EUNE" -> "EUROPE"
            else -> profile.region.trim().uppercase()
        })
        put("current_rank", profile.currentRank.name)
        put("previous_rank", profile.previousRank.name)
        put("primary", profile.primary.name)
        put("secondary", profile.secondary.name)
    }
}

class RemoteException(val statusCode: Int, message: String): Exception(message)

data class OnlinePlayer(val riotId: String, val role: String, val ready: Boolean, val ratingSeed: Int)

data class OnlineMatch(
    val id: String,
    val status: String,
    val deadline: Long,
    val readyCount: Int,
    val blue: List<OnlinePlayer>,
    val red: List<OnlinePlayer>,
    val isCaptain: Boolean = false,
    val ownReport: String? = null,
    val reportCount: Int = 0
)

data class OnlineResult(val id: String, val winner: String, val side: String, val resolvedAt: Long)

data class OcrSuggestions(val slot: Int, val matches: Int?, val winRate: Double?, val kda: Double?, val ranks: List<String>)

data class PlaySession(val id: String, val title: String, val region: String, val startsAt: Long, val endsAt: Long, val capacity: Int, val registered: Int, val waiting: Int, val status: String, val enrolled: Boolean, val roles: Map<String, Int>)

data class OnlineQueueState(
    val accountStatus: String,
    val queued: Boolean,
    val waiting: Int,
    val match: OnlineMatch?,
    val incidents: Int = 0,
    val blockedUntil: Long = 0,
    val sessionId: String? = null
)

fun JSONObject.toOnlineQueueState(): OnlineQueueState {
    fun org.json.JSONArray.toPlayers(): List<OnlinePlayer> = (0 until length()).map { i ->
        val row = getJSONObject(i)
        OnlinePlayer(row.optString("riot_id"), row.optString("role"),
            row.optBoolean("ready"), row.optInt("rating_seed"))
    }
    val matchObj = optJSONObject("match")
    val match = if (matchObj == null) null else OnlineMatch(
        matchObj.optString("id"), matchObj.optString("status"),
        matchObj.optLong("deadline"), matchObj.optInt("ready_count"),
        (matchObj.optJSONArray("blue") ?: org.json.JSONArray()).toPlayers(),
        (matchObj.optJSONArray("red") ?: org.json.JSONArray()).toPlayers(),
        matchObj.optBoolean("is_captain"), matchObj.optString("own_report").takeUnless { it.isBlank() || it == "null" },
        matchObj.optInt("report_count")
    )
    return OnlineQueueState(optString("account_status"), optBoolean("queued"),
        optInt("waiting_in_region"), match, optJSONObject("discipline")?.optInt("incidents") ?: 0,
        optJSONObject("discipline")?.optLong("blocked_until") ?: 0, optString("session_id").takeUnless { it.isBlank() || it == "null" })
}


data class SocialPlayer(val id: String, val riotId: String, val primary: String, val secondary: String, val relationship: String)
data class SessionInvitation(val id: String, val title: String, val sender: String, val startsAt: Long)
data class ComplaintStatus(val category: String, val status: String)
data class RecentPlayer(val matchId: String, val accountId: String, val riotId: String, val createdAt: Long)
data class CommunityData(val friends: List<SocialPlayer>, val invites: List<SessionInvitation>, val reports: List<ComplaintStatus>, val recent: List<RecentPlayer>, val confirmedComplaints: Int)
