package com.transfer.flash.server.auth

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Strict parsing of the device and server identity keys the hub is willing to certify or trust (SEC-07). */
object Spki {

    private val p256: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Parses [base64] as an X.509 SubjectPublicKeyInfo holding a NIST P-256 key whose point lies on the curve.
     * Returns null for anything else (other curves, RSA, truncated or non-canonical encodings, off-curve points).
     */
    fun parseP256(base64: String): ECPublicKey? {
        if (base64.isBlank() || base64.length > 512) return null
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey ?: return null
            // The canonical re-encoding must equal the input: rejects trailing garbage and alternative encodings.
            if (!key.encoded.contentEquals(bytes)) return null
            val params = key.params
            if (params.curve != p256.curve || params.order != p256.order || params.generator != p256.generator) return null
            if (!onCurve(key)) return null
            key
        } catch (_: Exception) {
            null
        }
    }

    fun isValidP256(base64: String): Boolean = parseP256(base64) != null

    private fun onCurve(key: ECPublicKey): Boolean {
        val point = key.w
        if (point == java.security.spec.ECPoint.POINT_INFINITY) return false
        val field = key.params.curve.field as? java.security.spec.ECFieldFp ?: return false
        val p = field.p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val a = key.params.curve.a
        val b = key.params.curve.b
        val left = y.modPow(BigInteger.TWO, p)
        val right = x.modPow(BigInteger.valueOf(3), p).add(a.multiply(x)).add(b).mod(p)
        return left == right
    }
}
