package dev.lolokeksu.scrimroom

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lolokeksu.scrimroom.data.RemoteClient
import dev.lolokeksu.scrimroom.data.RemoteException
import dev.lolokeksu.scrimroom.data.toOnlineQueueState
import dev.lolokeksu.scrimroom.model.PlayerProfile
import dev.lolokeksu.scrimroom.model.Rank
import dev.lolokeksu.scrimroom.model.Role
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.URL
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real Android HTTP stack against an isolated FastAPI instance. No production TLS bypass. */
@RunWith(AndroidJUnit4::class)
class RemoteClientIntegrationTest {
    @Test fun registrationEvidenceModerationAndTenPlayerReadyCheck() {
        val args = InstrumentationRegistry.getArguments()
        val encoded = args.getString("scrimTestCert").orEmpty()
        assumeTrue("Requires isolated HTTPS server supplied by the emulator CI", encoded.isNotEmpty())
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ci", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val images = mutableListOf<Uri>()
        try {
            // This override exists only in androidTest and is restored in finally.
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            val base = "https://10.0.2.2:8443"
            val api = RemoteClient(base)
            val stamp = System.currentTimeMillis().toString()
            repeat(2) { i ->
                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (i == 0) Color.BLUE else Color.RED)
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "scrim-ci-$stamp-$i.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrimRoomNetworkTests")
                    }))
                images.add(uri)
                checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            fun approve(accountId: String) {
                val c = URL("$base/v1/admin/accounts/$accountId/approve").openConnection() as HttpsURLConnection
                try {
                    c.requestMethod = "POST"
                    c.connectTimeout = 12000
                    c.readTimeout = 15000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("X-Admin-Key", "test-only-ephemeral-scrim-admin-key-for-local-ci")
                    assertEquals(200, c.responseCode)
                    c.inputStream.use { it.readBytes() }
                } finally { c.disconnect() }
            }
            val roles = Role.entries
            val tokens = (0 until 10).map { i ->
                val reply = api.register(PlayerProfile(
                    riotId = "CI${stamp.takeLast(8)}$i#EUW", region = "EUROPE",
                    currentRank = Rank.GRANDMASTER, previousRank = Rank.MASTER,
                    primary = roles[i % 5], secondary = roles[(i + 1) % 5]))
                val token = reply.getString("token")
                val account = reply.getJSONObject("account")
                assertEquals("PENDING_REVIEW", account.getString("status"))
                val denied = runCatching { api.join(token) }.exceptionOrNull() as? RemoteException
                assertEquals(403, denied?.statusCode)
                images.forEach { api.uploadEvidence(token, resolver, it) }
                assertEquals(2, api.account(token).getInt("evidence_count"))
                approve(account.getString("id"))
                assertEquals("APPROVED", api.account(token).getString("status"))
                token
            }
            api.join(tokens.first())
            assertTrue(api.state(tokens.first()).toOnlineQueueState().queued)
            api.leave(tokens.first())
            assertFalse(api.state(tokens.first()).toOnlineQueueState().queued)
            tokens.forEach { api.join(it) }
            val found = checkNotNull(api.state(tokens.first()).toOnlineQueueState().match)
            assertEquals("READY_CHECK", found.status)
            assertEquals(5, found.blue.size)
            assertEquals(5, found.red.size)
            assertEquals(roles.map { it.name }.toSet(), found.blue.map { it.role }.toSet())
            assertEquals(roles.map { it.name }.toSet(), found.red.map { it.role }.toSet())
            assertEquals(10, (found.blue + found.red).map { it.riotId }.toSet().size)
            assertTrue(found.deadline > found.serverNow)
            assertFalse(found.ownReady)
            api.ready(tokens.first(),found.id)
            assertTrue(checkNotNull(api.state(tokens.first()).toOnlineQueueState().match).ownReady)
            tokens.drop(1).forEach { api.ready(it, found.id) }
            val active = checkNotNull(api.state(tokens.first()).toOnlineQueueState().match)
            assertEquals("ACTIVE", active.status)
            assertEquals(10, active.readyCount)

            // Determine the captains from server-assigned positions, not registration order.
            val captains = tokens.filter { api.state(it).toOnlineQueueState().match?.isCaptain == true }
            assertEquals(2, captains.size)
            val host = captains.single { api.state(it).toOnlineQueueState().match?.lobbyHost == true }
            api.setLobby(host,found.id,"CI practice room")
            assertEquals("CI practice room",api.state(tokens.last()).toOnlineQueueState().match?.lobbyRoom)
            tokens.forEach { api.arriveLobby(it,found.id) }
            val arrived = checkNotNull(api.state(host).toOnlineQueueState().match)
            assertEquals(10,arrived.lobbyArrivals)
            assertTrue(arrived.ownArrived)
            captains.forEachIndexed { index, captain -> api.uploadResultEvidence(captain,found.id,resolver,images[index]) }
            assertEquals(2,api.state(host).toOnlineQueueState().match?.evidenceCount)
            assertTrue(checkNotNull(api.state(host).toOnlineQueueState().match).ownEvidence)
            val nonCaptain = tokens.first { it !in captains }
            val deniedReport = runCatching { api.reportResult(nonCaptain, found.id, "BLUE") }.exceptionOrNull() as? RemoteException
            assertEquals(403, deniedReport?.statusCode)
            assertEquals("ACTIVE", api.reportResult(captains[0], found.id, "BLUE").getString("status"))
            val waiting = checkNotNull(api.state(captains[1]).toOnlineQueueState().match)
            assertEquals(1, waiting.reportCount)
            assertNull(waiting.ownReport)
            assertEquals("CLOSED", api.reportResult(captains[1], found.id, "BLUE").getString("status"))
            assertEquals("CLOSED", api.reportResult(captains[1], found.id, "BLUE").getString("status"))
            tokens.forEach { token ->
                val results = api.results(token)
                assertEquals(1, results.size)
                assertEquals(found.id, results[0].id)
                assertEquals("BLUE", results[0].winner)
                val stats=api.statistics(token)
                assertEquals(1,stats.played)
                assertEquals(1,stats.calibrated)
                assertEquals(1,stats.roles.sumOf { it.played })
                assertEquals(1,stats.wins+stats.losses)
                assertTrue(results[0].role.isNotBlank())
                assertNull(api.state(token).toOnlineQueueState().match)
            }
            // Social operations use the real authenticated Android HTTP client.
            val firstToken = tokens.first()
            val secondToken = tokens[1]
            val firstAccount = api.account(firstToken)
            val secondAccount = api.account(secondToken)
            val firstId = firstAccount.getString("id")
            val secondId = secondAccount.getString("id")
            assertEquals(secondId, api.findPlayer(firstToken, secondAccount.getString("riot_id")).id)
            api.requestFriend(firstToken,secondId)
            assertEquals("INCOMING", api.community(secondToken).friends.single().relationship)
            api.friendAction(secondToken,firstId,true)
            assertEquals("FRIEND", api.community(firstToken).friends.single().relationship)
            val target = api.community(firstToken).recent.first { it.accountId == secondId }
            api.complain(firstToken,target.matchId,target.accountId,"AFK","Synthetic HTTPS integration check")
            assertEquals("PENDING", api.community(firstToken).reports.single().status)
            assertTrue(api.community(secondToken).reports.isEmpty())

            // A real scheduled session, distinct from the ordinary queue.
            val starts = System.currentTimeMillis() / 1000 + 5
            val c = URL("$base/v1/admin/sessions").openConnection() as HttpsURLConnection
            val sessionId: String
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 12000
                c.readTimeout = 15000
                c.instanceFollowRedirects = false
                c.doOutput = true
                c.setRequestProperty("X-Admin-Key", "test-only-ephemeral-scrim-admin-key-for-local-ci")
                c.setRequestProperty("Content-Type", "application/json")
                val data = JSONObject().put("title","CI practice").put("region","EUROPE")
                    .put("starts_at",starts).put("ends_at",starts+600).put("capacity",10)
                c.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
                assertEquals(201,c.responseCode)
                sessionId = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
            } finally { c.disconnect() }
            api.signup(firstToken,sessionId)
            api.inviteFriend(firstToken,secondId,sessionId)
            val invitation = api.community(secondToken).invites.single()
            assertEquals("CI practice",invitation.title)
            assertFalse(api.sessions(secondToken).single { it.id==sessionId }.enrolled)
            api.dismissInvite(secondToken,invitation.id)
            assertTrue(api.community(secondToken).invites.isEmpty())
            tokens.forEach { token -> api.signup(token,sessionId) }
            val session = api.sessions(tokens.first()).single { it.id == sessionId }
            assertTrue(session.enrolled)
            assertEquals(10,session.registered)
            api.withdraw(tokens.last(),sessionId)
            assertFalse(api.sessions(tokens.last()).single { it.id == sessionId }.enrolled)
            api.signup(tokens.last(),sessionId)
            val endWait=System.currentTimeMillis()+15000
            while(api.sessions(tokens.first()).single { it.id==sessionId }.status!="OPEN") {
                check(System.currentTimeMillis()<endWait)
                Thread.sleep(250)
            }
            tokens.take(9).forEach { api.join(it,sessionId) }
            api.join(tokens.last())
            assertNull(api.state(tokens.first()).toOnlineQueueState().match)
            assertEquals(sessionId, api.state(tokens.first()).toOnlineQueueState().sessionId)
            api.leave(tokens.last())
            api.join(tokens.last(),sessionId)
            assertNotNull(api.state(tokens.first()).toOnlineQueueState().match)
            // Recovery retains account identity, privacy and assigned match.
            assertTrue(api.discoverable(firstToken))
            assertFalse(api.setDiscoverable(firstToken,false))
            assertFalse(api.discoverable(firstToken))
            val unread=api.inbox(firstToken).filter{!it.read}
            assertTrue(unread.isNotEmpty())
            api.readInbox(firstToken,unread.map{it.id})
            assertTrue(api.inbox(firstToken).all{it.read})
            val code=api.issueRecovery(firstToken)
            val recovered=api.recover(code)
            val replacement=recovered.getString("token")
            assertEquals(firstId,recovered.getJSONObject("account").getString("id"))
            assertFalse(api.discoverable(replacement))
            assertNotNull(api.state(replacement).toOnlineQueueState().match)
            assertEquals(401,(runCatching{api.account(firstToken)}.exceptionOrNull() as? RemoteException)?.statusCode)
            assertEquals(401,(runCatching{api.recover(code)}.exceptionOrNull() as? RemoteException)?.statusCode)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
            images.forEach { resolver.delete(it, null, null) }
        }
    }
}
