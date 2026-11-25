package xyz.qingjian.writer

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.mangi.eta.agent.auth.AuthorizationDeclined
import io.github.mangi.eta.agent.auth.LoopbackAuthorizationReceiver
import io.github.mangi.eta.agent.auth.verifyOpenAiIdentity
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanAuthorizationTest {
    @Test
    fun callbackIsBoundToExactlyOneStateAndCarriesTheIssuedClientId() {
        LoopbackAuthorizationReceiver().use { receiver ->
            val ok = receiver.validateCallback("/auth/callback?state=expected&code=test-code&client_id=oaiapp_testing", "expected")
            assertEquals("oaiapp_testing", ok?.clientId)
            assertEquals("test-code", ok?.code)
            assertNull(receiver.validateCallback("/auth/callback?state=other&code=test-code", "expected"))
            assertNull(receiver.validateCallback("/auth/callback?state=expected&state=other&code=test-code", "expected"))
            assertNull(receiver.validateCallback("/auth/callback?state=other&error=access_denied", "expected"))
            assertThrows(AuthorizationDeclined::class.java) {
                receiver.validateCallback("/auth/callback?state=expected&error=access_denied", "expected")
            }
        }
    }

    @Test
    fun identitySignatureNonceAndIssuedClientMustMatch() {
        val now = 1_790_000_000_000L
        val key = RSAKeyGenerator(2048).keyID("fixture").generate()
        val claims = JWTClaimsSet.Builder().issuer("https://auth.openai.com").audience("oaiapp_fixture")
            .subject("fixture-user").issueTime(Date(now)).expirationTime(Date(now + 60_000))
            .claim("nonce", "original-nonce").build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(), claims).apply {
            sign(RSASSASigner(key))
        }.serialize()
        val keys = JWKSet(key.toPublicJWK())
        assertEquals("fixture-user", verifyOpenAiIdentity(token, "oaiapp_fixture", "original-nonce", keys, now).subject)
        assertThrows(IllegalArgumentException::class.java) {
            verifyOpenAiIdentity(token, "oaiapp_other", "original-nonce", keys, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyOpenAiIdentity(token, "oaiapp_fixture", "another-nonce", keys, now)
        }
    }
}
