package top.niunaijun.blackbox.fake.network.vmess;

/**
 * Minimal Base64 decoder.
 *
 * The VMess core is kept free of android.* so it can be exercised on a desktop JVM against a real
 * server; android.util.Base64 would tie it to the device.
 */
final class Base64 {

    private static final byte[] ALPHABET = new byte[256];

    static {
        for (int i = 0; i < ALPHABET.length; i++) ALPHABET[i] = -1;
        String standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < standard.length(); i++) ALPHABET[standard.charAt(i)] = (byte) i;
        String urlSafe = "-_";
        for (int i = 0; i < urlSafe.length(); i++) ALPHABET[urlSafe.charAt(i)] = (byte) (62 + i);
    }

    private Base64() {
    }

    /**
     * Decodes standard or URL-safe Base64, with or without padding, ignoring whitespace and any
     * characters outside the alphabet (subscriptions sometimes wrap long payloads).
     */
    static byte[] decode(String input) {
        byte[] out = new byte[input.length() * 3 / 4 + 3];
        int outLen = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '=') break;
            if (c > 255) continue;
            byte value = ALPHABET[c];
            if (value < 0) continue;
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outLen++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        byte[] trimmed = new byte[outLen];
        System.arraycopy(out, 0, trimmed, 0, outLen);
        return trimmed;
    }
}
