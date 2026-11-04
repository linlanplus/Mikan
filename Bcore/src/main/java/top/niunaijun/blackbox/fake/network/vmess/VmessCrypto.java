package top.niunaijun.blackbox.fake.network.vmess;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptographic pieces of VMess AEAD: the auth ID, the sealed request header, and the chunked
 * AES-128-GCM body.
 *
 * The KDF path names come from the reference implementation and must match it byte for byte; they
 * are not free choices.
 */
final class VmessCrypto {

    static final byte[] KDF_AUTH_ID_KEY = VmessKdf.utf8("AES Auth ID Encryption");
    static final byte[] KDF_RESP_HEADER_LEN_KEY = VmessKdf.utf8("AEAD Resp Header Len Key");
    static final byte[] KDF_RESP_HEADER_LEN_IV = VmessKdf.utf8("AEAD Resp Header Len IV");
    static final byte[] KDF_RESP_HEADER_KEY = VmessKdf.utf8("AEAD Resp Header Key");
    static final byte[] KDF_RESP_HEADER_IV = VmessKdf.utf8("AEAD Resp Header IV");
    static final byte[] KDF_HEADER_LEN_KEY = VmessKdf.utf8("VMess Header AEAD Key_Length");
    static final byte[] KDF_HEADER_LEN_IV = VmessKdf.utf8("VMess Header AEAD Nonce_Length");
    static final byte[] KDF_HEADER_KEY = VmessKdf.utf8("VMess Header AEAD Key");
    static final byte[] KDF_HEADER_IV = VmessKdf.utf8("VMess Header AEAD Nonce");

    /** {@code SecurityType_AES128_GCM}. Sending this explicitly avoids the ChaCha path entirely. */
    static final int SECURITY_AES128_GCM = 3;

    static final int GCM_TAG_LENGTH_BITS = 128;
    static final int GCM_TAG_BYTES = 16;
    static final int NONCE_LENGTH = 12;

    static final SecureRandom RANDOM = new SecureRandom();

    private VmessCrypto() {
    }

    /** MD5 of the UUID followed by a fixed suffix; every other derivation starts from this. */
    static byte[] cmdKey(String uuid) {
        byte[] id = uuidBytes(uuid);
        if (id == null) return null;
        byte[] suffix = VmessKdf.utf8("c48619fe-8f02-49e0-b9e9-edf763e17e21");
        return VmessKdf.md5(VmessKdf.concat(id, suffix));
    }

    static byte[] uuidBytes(String uuid) {
        if (uuid == null) return null;
        String hex = uuid.replace("-", "").trim();
        if (hex.length() != 32) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * 16 bytes that identify the user without revealing the UUID: AES-ECB over the current time,
     * four random bytes, and a CRC32 of those twelve.
     */
    static byte[] createAuthId(byte[] cmdKey, long unixSeconds) {
        byte[] plain = new byte[16];
        for (int i = 0; i < 8; i++) {
            plain[i] = (byte) (unixSeconds >>> (56 - 8 * i));
        }
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, plain, 8, 4);

        CRC32 crc = new CRC32();
        crc.update(plain, 0, 12);
        long checksum = crc.getValue();
        for (int i = 0; i < 4; i++) {
            plain[12 + i] = (byte) (checksum >>> (24 - 8 * i));
        }

        byte[] key = new VmessKdf(KDF_AUTH_ID_KEY).derive16(cmdKey);
        return ecb(Cipher.ENCRYPT_MODE, key, plain);
    }

    /**
     * Seals the request header for the AEAD handshake.
     *
     * On the wire: auth ID (16) ‖ sealed length (18) ‖ connection nonce (8) ‖ sealed payload. Both
     * seals use the auth ID as additional data, so the server can reject a forged or replayed one
     * before touching the payload.
     */
    static byte[] sealRequestHeader(byte[] cmdKey, byte[] headerPlaintext, byte[] authId) {
        byte[] nonce = new byte[8];
        RANDOM.nextBytes(nonce);

        byte[] lengthPlain = new byte[2];
        int length = headerPlaintext.length;
        lengthPlain[0] = (byte) (length >>> 8);
        lengthPlain[1] = (byte) length;

        byte[] lengthKey = new VmessKdf(KDF_HEADER_LEN_KEY, authId, nonce).derive16(cmdKey);
        byte[] lengthIv = new VmessKdf(KDF_HEADER_LEN_IV, authId, nonce).derive(cmdKey);
        byte[] sealedLength = gcmSeal(lengthKey, Arrays.copyOf(lengthIv, NONCE_LENGTH), lengthPlain, authId);

        byte[] payloadKey = new VmessKdf(KDF_HEADER_KEY, authId, nonce).derive16(cmdKey);
        byte[] payloadIv = new VmessKdf(KDF_HEADER_IV, authId, nonce).derive(cmdKey);
        byte[] sealedPayload = gcmSeal(payloadKey, Arrays.copyOf(payloadIv, NONCE_LENGTH), headerPlaintext, authId);

        byte[] out = new byte[16 + sealedLength.length + 8 + sealedPayload.length];
        int offset = 0;
        System.arraycopy(authId, 0, out, offset, 16);
        offset += 16;
        System.arraycopy(sealedLength, 0, out, offset, sealedLength.length);
        offset += sealedLength.length;
        System.arraycopy(nonce, 0, out, offset, 8);
        offset += 8;
        System.arraycopy(sealedPayload, 0, out, offset, sealedPayload.length);
        return out;
    }

    static byte[] gcmSeal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("GCM seal failed: " + e.getMessage(), e);
        }
    }

    static byte[] gcmOpen(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("GCM open failed: " + e.getMessage(), e);
        }
    }

    static byte[] ecb(int mode, byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-ECB failed: " + e.getMessage(), e);
        }
    }

    /**
     * Chunk nonce: a copy of the IV whose first two bytes carry a big-endian counter. One counter
     * runs for the whole stream in one direction, so every chunk gets a distinct nonce.
     */
    static final class ChunkNonce {
        private final byte[] base;
        private int counter;

        ChunkNonce(byte[] iv) {
            base = Arrays.copyOf(iv, NONCE_LENGTH);
        }

        byte[] next() {
            byte[] out = Arrays.copyOf(base, NONCE_LENGTH);
            out[0] = (byte) (counter >>> 8);
            out[1] = (byte) counter;
            counter = (counter + 1) & 0xFFFF;
            return out;
        }
    }
}
