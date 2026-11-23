package xb.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH

// optimal 在載入時註冊 CryptoKit 和 CommonCrypto，Default 依演算法挑其中一個（CBC／PBKDF2 走 CommonCrypto，GCM／ECDH 走 CryptoKit）。
internal actual val platformProvider: CryptographyProvider = CryptographyProvider.Default

internal actual fun newEcdhPair(): EcdhPair = CkEcdhPair()

private class CkEcdhPair : EcdhPair {
    private val pair = platformProvider.get(ECDH).keyPairGenerator(EC.Curve.P256).generateKeyBlocking()

    override val publicDer: ByteArray get() = pair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.DER)

    override fun agree(theirsDer: ByteArray): ByteArray {
        val theirs = platformProvider.get(ECDH).publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.DER, theirsDer)
        return pair.privateKey.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(theirs)
    }
}
