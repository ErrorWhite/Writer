import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

public class VerifyApk {
    public static void main(String[] args) throws Exception {
        var result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) {
            throw new IllegalStateException("APK verification failed: " + result.getAllErrors());
        }
        var certificates = result.getSignerCertificates();
        if (certificates.size() != 1) {
            throw new IllegalStateException("Expected one APK signer, found " + certificates.size());
        }
        var encodedCertificate = certificates.getFirst().getEncoded();
        var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedCertificate));
        if (!fingerprint.equals(args[1])) {
            throw new IllegalStateException("Unexpected APK signing certificate: " + fingerprint);
        }
        System.out.println("Signing certificate SHA-256: " + fingerprint);
    }
}
