package top.niunaijun.blackbox.fake.network.vmess;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * VMess AEAD key derivation.
 *
 * The reference implementation does not chain HMACs over a message; it chains the *hash function*
 * itself. Level 0 is SHA-256, level 1 is HMAC keyed with the VMess salt and using SHA-256 as its
 * hash, level 2 is HMAC keyed with the first path element and using level 1 as its hash, and so on.
 * The final value is the top level applied to the key.
 *
 * Path elements are byte arrays, not strings: two of them are raw random bytes in the reference
 * implementation, and decoding them through a charset would silently corrupt them.
 */
final class VmessKdf {

    static final byte[] SALT = utf8("VMess AEAD KDF");
    private static final int BLOCK_SIZE = 64;

    private final NestedHash top;

    VmessKdf(byte[]... path) {
        NestedHash chain = new NestedHash(SALT, null);
        for (byte[] element : path) {
            chain = new NestedHash(element, chain);
        }
        top = chain;
    }

    /** 32 bytes of derived key material for {@code key}. */
    byte[] derive(byte[] key) {
        return top.digest(key);
    }

    /** First 16 bytes of {@link #derive(byte[])}, which is what every AES key uses. */
    byte[] derive16(byte[] key) {
        return Arrays.copyOf(derive(key), 16);
    }

    /** A hash function built from HMAC, itself able to act as the hash of the next layer. */
    private static final class NestedHash {
        private final byte[] keyBlock;
        private final NestedHash inner;

        NestedHash(byte[] key, NestedHash inner) {
            byte[] normalized = key.length > BLOCK_SIZE ? rawSha256(key) : key;
            keyBlock = new byte[BLOCK_SIZE];
            System.arraycopy(normalized, 0, keyBlock, 0, normalized.length);
            this.inner = inner;
        }

        byte[] digest(byte[] message) {
            byte[] ipad = new byte[BLOCK_SIZE];
            byte[] opad = new byte[BLOCK_SIZE];
            for (int i = 0; i < BLOCK_SIZE; i++) {
                ipad[i] = (byte) (keyBlock[i] ^ 0x36);
                opad[i] = (byte) (keyBlock[i] ^ 0x5c);
            }
            byte[] innerDigest = hash(concat(ipad, message));
            return hash(concat(opad, innerDigest));
        }

        private byte[] hash(byte[] data) {
            return inner == null ? rawSha256(data) : inner.digest(data);
        }
    }

    private static byte[] rawSha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data) {
        return rawSha256(data);
    }

    static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] utf8(String value) {
        return value.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }
}
