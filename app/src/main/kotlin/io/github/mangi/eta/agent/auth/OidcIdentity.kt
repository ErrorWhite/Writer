package io.github.mangi.eta.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyOperation
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest

internal data class VerifiedIdentity(val subject: String, val email: String, val name: String)

/** Adapted from Eta's OidcTokenVerifier for the OpenAI-issued dynamic client ID. */
internal fun verifyOpenAiIdentity(
    encoded: String, clientId: String, nonce: String?, keys: JWKSet, now: Long = System.currentTimeMillis()
): VerifiedIdentity = try {
    require(encoded.length in 1..65_536)
    val jwt = SignedJWT.parse(encoded)
    val algorithm = jwt.header.algorithm
    require(algorithm in setOf(JWSAlgorithm.RS256, JWSAlgorithm.ES256))
    require(!jwt.header.keyID.isNullOrBlank() && jwt.header.criticalParams.isNullOrEmpty())
    val key = requireNotNull(keys.getKeyByKeyId(jwt.header.keyID))
    require(!key.isPrivate && (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE))
    require(key.keyOperations.isNullOrEmpty() || KeyOperation.VERIFY in key.keyOperations)
    require(key.algorithm == null || key.algorithm == algorithm)
    val verifier = when {
        key is RSAKey && algorithm == JWSAlgorithm.RS256 -> {
            require(key.modulus.decodeToBigInteger().bitLength() >= 2048)
            RSASSAVerifier(key.toRSAPublicKey())
        }
        key is ECKey && algorithm == JWSAlgorithm.ES256 -> ECDSAVerifier(key.toECPublicKey())
        else -> error("Unsupported identity signing key")
    }
    require(jwt.verify(verifier))
    val claims = jwt.jwtClaimsSet
    require(claims.issuer == "https://auth.openai.com" && clientId in claims.audience)
    val authorizedParty = claims.getStringClaim("azp")
    if (claims.audience.size != 1 || authorizedParty != null) require(authorizedParty == clientId)
    require(requireNotNull(claims.expirationTime).time > now - 30_000)
    require(requireNotNull(claims.issueTime).time <= now + 30_000)
    claims.notBeforeTime?.let { require(it.time <= now + 30_000) }
    if (nonce != null) require(MessageDigest.isEqual(nonce.toByteArray(), claims.getStringClaim("nonce").orEmpty().toByteArray()))
    val subject = claims.subject.orEmpty().also { require(it.isNotBlank() && it.length <= 512) }
    VerifiedIdentity(subject, claims.getStringClaim("email").orEmpty().take(254), claims.getStringClaim("name").orEmpty().take(120))
} catch (_: Exception) {
    throw IllegalArgumentException("无法验证 ChatGPT 登录身份，请重新授权。")
}
