package top.niunaijun.blackbox.fake.network.vmess;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One VMess server: where to connect and which identity to present.
 *
 * Only plain TCP is supported, optionally wrapped in TLS. Transports that need their own framing
 * (WebSocket, gRPC, HTTP/2) are rejected at parse time rather than silently producing a connection
 * that never comes up.
 */
public final class VmessNode {

    public final String host;
    public final int port;
    public final String uuid;
    /** Reported in logs only; AEAD does not use it. */
    public final int alterId;
    public final boolean tls;
    public final String sni;
    public final String name;

    private VmessNode(String host, int port, String uuid, int alterId, boolean tls, String sni, String name) {
        this.host = host;
        this.port = port;
        this.uuid = uuid;
        this.alterId = alterId;
        this.tls = tls;
        this.sni = sni;
        this.name = name;
    }

    /** Parses a {@code vmess://<base64 json>} share link, or returns null if it is not usable here. */
    public static VmessNode fromLink(String link) {
        if (link == null) return null;
        String trimmed = link.trim();
        if (!trimmed.startsWith("vmess://")) return null;
        String payload = trimmed.substring("vmess://".length());
        int hash = payload.indexOf('#');
        if (hash >= 0) payload = payload.substring(0, hash);
        String json;
        try {
            json = new String(Base64.decode(payload), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, String> fields = readFlatJson(json);
        if (fields == null) return null;
        return fromFields(fields);
    }

    /** Builds a node from the flat key/value pairs a share link or a Clash entry provides. */
    public static VmessNode fromFields(Map<String, String> fields) {
        String host = fields.get("add");
        if (host == null || host.isEmpty()) return null;
        int port;
        try {
            port = Integer.parseInt(fields.get("port"));
        } catch (Exception e) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;

        String uuid = fields.get("id");
        if (uuid == null || uuid.isEmpty()) return null;
        uuid = uuid.trim();

        int alterId = 0;
        try {
            String aid = fields.get("aid");
            if (aid == null) aid = fields.get("alterId");
            if (aid != null && !aid.isEmpty()) alterId = Integer.parseInt(aid.trim());
        } catch (NumberFormatException ignored) {
            // A bad alterId does not affect the AEAD handshake.
        }

        String network = fields.get("net");
        if (network == null) network = fields.get("network");
        if (network != null && !network.isEmpty() && !"tcp".equals(network.toLowerCase(Locale.ROOT))) {
            return null; // ws/grpc/h2 would need a transport layer we do not have
        }

        String tlsField = fields.get("tls");
        boolean tls = "tls".equalsIgnoreCase(tlsField) || "true".equalsIgnoreCase(tlsField);

        String sni = fields.get("sni");
        if (sni == null || sni.isEmpty()) sni = fields.get("host");
        if (sni == null || sni.isEmpty()) sni = host;

        String name = fields.get("ps");
        if (name == null || name.isEmpty()) name = fields.get("name");
        if (name == null) name = host;

        return new VmessNode(host, port, uuid, alterId, tls, sni, name);
    }

    /**
     * Reads the string values out of a flat JSON object. Share links are always flat, so a full
     * parser would be dead weight here; nested values are skipped.
     */
    private static Map<String, String> readFlatJson(String json) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        int n = json.length();
        while (i < n) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = stringEnd(json, keyStart);
            if (keyEnd < 0) break;
            String key = unescape(json.substring(keyStart + 1, keyEnd));
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) break;
            int valueStart = colon + 1;
            while (valueStart < n && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
            if (valueStart >= n) break;
            char c = json.charAt(valueStart);
            if (c == '"') {
                int valueEnd = stringEnd(json, valueStart);
                if (valueEnd < 0) break;
                out.put(key, unescape(json.substring(valueStart + 1, valueEnd)));
                i = valueEnd + 1;
            } else if (c == '{' || c == '[') {
                int close = matchingBracket(json, valueStart);
                if (close < 0) break;
                i = close + 1;
            } else {
                int end = valueStart;
                while (end < n && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
                out.put(key, json.substring(valueStart, end).trim());
                i = end;
            }
        }
        return out;
    }

    /** Index of the closing quote of the string starting at {@code openQuote}, skipping escapes. */
    private static int stringEnd(String s, int openQuote) {
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int matchingBracket(String s, int open) {
        char opener = s.charAt(open);
        char closer = opener == '{' ? '}' : ']';
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                int end = stringEnd(s, i);
                if (end < 0) return -1;
                i = end;
            } else if (c == opener) {
                depth++;
            } else if (c == closer) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                    break;
                default: out.append(next); break;
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + (tls ? ", tls" : "") + ")";
    }
}
