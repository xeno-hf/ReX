package xb.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

// 用 JDK（系統註冊的所有 JCA provider），不用 .JDK 屬性：屬性版會先用 ServiceLoader 找額外的 provider，
// Android 的 release 版經過 R8，註冊檔可能被改名或拿掉。
internal actual val platformProvider: CryptographyProvider = CryptographyProvider.JDK()

// ECDH 不走 cryptography-kotlin：它會重複使用同一個 KeyFactory，Android 上只要拒絕過一把壞公鑰，
// 那個 KeyFactory 就卡在 AndroidKeyStore，之後正確的公鑰也全部失敗，要重開 App 才好（cryptography-kotlin issue #159）。
// 這裡每次匯入都開新的 KeyFactory，跟換函式庫之前的寫法一樣。
internal actual fun newEcdhPair(): EcdhPair = JcaEcdhPair()

private class JcaEcdhPair : EcdhPair {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override val publicDer: ByteArray get() = pair.public.encoded

    override fun agree(theirsDer: ByteArray): ByteArray {
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirsDer))
        val curve = (theirs as? java.security.interfaces.ECPublicKey)?.params?.curve
        require(curve == (pair.public as java.security.interfaces.ECPublicKey).params.curve) { "對方的公鑰不是 P-256" }
        return KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(theirs, true) }.generateSecret()
    }
}
