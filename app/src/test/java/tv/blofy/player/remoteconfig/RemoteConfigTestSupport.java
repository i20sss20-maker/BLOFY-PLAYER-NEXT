package tv.blofy.player.remoteconfig;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

final class RemoteConfigTestSupport {
    static final String KID = "test-key-1";

    private RemoteConfigTestSupport() {}

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String publicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    static RemoteConfigEnvelope envelope(String payload, KeyPair pair) throws Exception {
        return envelope(payload, pair,
                "{\"alg\":\"ES256\",\"kid\":\"" + KID
                        + "\",\"typ\":\"blofy-remote-config+jws\"}");
    }

    static RemoteConfigEnvelope envelope(String payload, KeyPair pair, String header)
            throws Exception {
        String encodedHeader = encode(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String encodedPayload = encode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(signingInput.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return new RemoteConfigEnvelope(signingInput + "." + encode(derToJose(signer.sign())));
    }

    static String global(long revision, long issued, long expires, String policy) {
        return "{\"schemaVersion\":1,\"scope\":\"global\","
                + "\"aud\":\"tv.blofy.player\",\"revision\":" + revision
                + ",\"iat\":" + issued + ",\"nbf\":" + issued
                + ",\"exp\":" + expires + ",\"minVersionCode\":1,"
                + "\"policy\":" + policy + "}";
    }

    static String provider(long revision, long issued, long expires, String profileId,
                           int profileRevision, String policy) {
        return "{\"schemaVersion\":1,\"scope\":\"provider\","
                + "\"aud\":\"tv.blofy.player\",\"revision\":" + revision
                + ",\"iat\":" + issued + ",\"nbf\":" + issued
                + ",\"exp\":" + expires + ",\"minVersionCode\":1,"
                + "\"profileId\":\"" + profileId + "\","
                + "\"profileRevision\":" + profileRevision + ","
                + "\"policy\":" + policy + "}";
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] derToJose(byte[] der) {
        int offset = 0;
        if ((der[offset++] & 0xff) != 0x30) throw new IllegalArgumentException();
        int sequenceLength = der[offset++] & 0xff;
        if (sequenceLength != der.length - offset) throw new IllegalArgumentException();
        if ((der[offset++] & 0xff) != 0x02) throw new IllegalArgumentException();
        int rLength = der[offset++] & 0xff;
        int rStart = offset;
        offset += rLength;
        if ((der[offset++] & 0xff) != 0x02) throw new IllegalArgumentException();
        int sLength = der[offset++] & 0xff;
        int sStart = offset;
        if (offset + sLength != der.length) throw new IllegalArgumentException();
        byte[] result = new byte[64];
        copyInteger(der, rStart, rLength, result, 0);
        copyInteger(der, sStart, sLength, result, 32);
        return result;
    }

    private static void copyInteger(byte[] source, int start, int length,
                                    byte[] target, int targetStart) {
        while (length > 32 && source[start] == 0) { start++; length--; }
        if (length > 32) throw new IllegalArgumentException();
        System.arraycopy(source, start, target, targetStart + 32 - length, length);
    }
}
