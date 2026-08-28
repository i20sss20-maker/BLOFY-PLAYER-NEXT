package tv.blofy.player.remoteconfig;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.ECPublicKey;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies compact JWS ES256 before any Remote Config field is interpreted. */
public final class RemoteConfigVerifier {
    private static final String TYPE = "blofy-remote-config+jws";
    private static final int MAX_HEADER_BYTES = 2048;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final BigInteger P256_P = hex(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF");
    private static final BigInteger P256_A = hex(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC");
    private static final BigInteger P256_B = hex(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B");
    private static final BigInteger P256_GX = hex(
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296");
    private static final BigInteger P256_GY = hex(
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5");
    private static final BigInteger P256_N = hex(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551");

    public interface TrustedKeys {
        PublicKey find(String keyId);
    }

    public static final TrustedKeys NONE = keyId -> null;

    private final TrustedKeys trustedKeys;

    public RemoteConfigVerifier(TrustedKeys trustedKeys) {
        this.trustedKeys = trustedKeys == null ? NONE : trustedKeys;
    }

    public VerifiedPayload verify(RemoteConfigEnvelope envelope) throws RemoteConfigException {
        if (envelope == null) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "remote config envelope is missing");
        }
        String[] parts = envelope.compactJws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()
                || parts[2].isEmpty()) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "compact JWS must contain three parts");
        }
        try {
            Map<String, Object> header = StrictJson.object(
                    StrictJson.parse(decodeUrl(parts[0]), MAX_HEADER_BYTES));
            if (header.size() != 3
                    || !"ES256".equals(string(header, "alg"))
                    || !TYPE.equals(string(header, "typ"))) {
                throw new RemoteConfigException(RemoteConfigException.Reason.UNTRUSTED,
                        "remote config JWS header is not allowlisted");
            }
            String keyId = string(header, "kid");
            if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new RemoteConfigException(RemoteConfigException.Reason.UNTRUSTED,
                        "remote config key id is invalid");
            }
            PublicKey key = trustedKeys.find(keyId);
            if (!isP256(key)) {
                throw new RemoteConfigException(RemoteConfigException.Reason.UNTRUSTED,
                        "remote config key is not trusted");
            }
            byte[] rawSignature = decodeUrl(parts[2]);
            if (rawSignature.length != 64) {
                throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                        "ES256 signature must be 64 bytes");
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(rawSignature))) {
                throw new RemoteConfigException(RemoteConfigException.Reason.SIGNATURE,
                        "remote config signature is invalid");
            }
            byte[] payloadBytes = decodeUrl(parts[1]);
            Map<String, Object> payload = freezeObject(StrictJson.object(
                    StrictJson.parse(payloadBytes, MAX_PAYLOAD_BYTES)));
            return new VerifiedPayload(keyId, payload, sha256(envelope.compactJws));
        } catch (RemoteConfigException failure) {
            throw failure;
        } catch (StrictJson.ParseException failure) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "signed remote config JSON is invalid", failure);
        } catch (IllegalArgumentException failure) {
            throw new RemoteConfigException(RemoteConfigException.Reason.MALFORMED,
                    "compact JWS encoding is invalid", failure);
        } catch (Exception failure) {
            throw new RemoteConfigException(RemoteConfigException.Reason.SIGNATURE,
                    "remote config verification failed", failure);
        }
    }

    public static TrustedKeys oneEs256Key(String keyId, String base64Spki) {
        String cleanId = keyId == null ? "" : keyId.trim();
        String cleanKey = base64Spki == null ? "" : base64Spki.trim();
        if (!cleanId.matches("[A-Za-z0-9._-]{1,64}") || cleanKey.isEmpty()) return NONE;
        try {
            PublicKey parsed = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(decodeFlexibleBase64(cleanKey)));
            if (!isP256(parsed)) return NONE;
            return requested -> cleanId.equals(requested) ? parsed : null;
        } catch (Exception invalid) {
            return NONE;
        }
    }

    public static final class VerifiedPayload {
        public final String keyId;
        public final Map<String, Object> claims;
        public final String compactDigest;

        VerifiedPayload(String keyId, Map<String, Object> claims, String compactDigest) {
            this.keyId = keyId;
            this.claims = Collections.unmodifiableMap(claims);
            this.compactDigest = compactDigest;
        }
    }

    private static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static boolean isP256(PublicKey key) {
        if (!(key instanceof ECPublicKey)) return false;
        ECPublicKey ec = (ECPublicKey) key;
        ECParameterSpec params = ec.getParams();
        if (params == null || params.getCurve() == null
                || !(params.getCurve().getField() instanceof ECFieldFp)
                || params.getGenerator() == null) return false;
        ECFieldFp field = (ECFieldFp) params.getCurve().getField();
        return P256_P.equals(field.getP())
                && P256_A.equals(params.getCurve().getA())
                && P256_B.equals(params.getCurve().getB())
                && P256_GX.equals(params.getGenerator().getAffineX())
                && P256_GY.equals(params.getGenerator().getAffineY())
                && P256_N.equals(params.getOrder())
                && params.getCofactor() == 1;
    }

    private static BigInteger hex(String value) { return new BigInteger(value, 16); }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<Object>) value) result.add(freeze(item));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeObject(Map<String, Object> value) {
        return (Map<String, Object>) freeze(value);
    }

    private static byte[] decodeFlexibleBase64(String value) {
        String clean = value;
        while (clean.endsWith("=")) clean = clean.substring(0, clean.length() - 1);
        clean = clean.replace('+', '-').replace('/', '_');
        return decodeUrl(clean);
    }

    private static byte[] decodeUrl(String value) {
        if (value == null || value.isEmpty() || value.indexOf('=') >= 0
                || value.length() % 4 == 1) throw new IllegalArgumentException("invalid base64url");
        byte[] result = new byte[(value.length() * 6) / 8];
        int accumulator = 0;
        int bits = 0;
        int output = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            int digit;
            if (c >= 'A' && c <= 'Z') digit = c - 'A';
            else if (c >= 'a' && c <= 'z') digit = c - 'a' + 26;
            else if (c >= '0' && c <= '9') digit = c - '0' + 52;
            else if (c == '-') digit = 62;
            else if (c == '_') digit = 63;
            else throw new IllegalArgumentException("invalid base64url");
            accumulator = (accumulator << 6) | digit;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                result[output++] = (byte) (accumulator >> bits);
                accumulator &= bits == 0 ? 0 : (1 << bits) - 1;
            }
        }
        if (accumulator != 0 || output != result.length) {
            throw new IllegalArgumentException("non-canonical base64url");
        }
        return result;
    }

    private static byte[] joseToDer(byte[] jose) {
        byte[] r = positiveInteger(jose, 0);
        byte[] s = positiveInteger(jose, 32);
        int contentLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + contentLength];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) contentLength;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] positiveInteger(byte[] source, int start) {
        int first = start;
        int end = start + 32;
        while (first < end - 1 && source[first] == 0) first++;
        boolean prefix = (source[first] & 0x80) != 0;
        byte[] result = new byte[end - first + (prefix ? 1 : 0)];
        System.arraycopy(source, first, result, prefix ? 1 : 0, end - first);
        return result;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
        StringBuilder result = new StringBuilder(digest.length * 2);
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte item : digest) {
            int number = item & 0xff;
            result.append(hex[number >>> 4]).append(hex[number & 0x0f]);
        }
        return result.toString();
    }
}
