package top.niunaijun.blackbox.fake.network;

import android.content.Context;
import android.system.Os;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Slog;

/** The host writes the catalog. Each guest reads one complete snapshot before filesystem redirection. */
public final class ProxyConfig {
    private static volatile boolean routesTcp;
    public static boolean routesTcp() { return routesTcp; }
    private ProxyConfig() {}
    private static Context context() { return BlackBoxCore.getContext(); }
    private static File file() { return new File(context().getNoBackupFilesDir(), "proxy-nodes.json"); }

    public static final class Choice {
        public final String id, name, protocol, sourceId, sourceLabel;
        public final int number;
        public final boolean used;
        private final String outbound;

        public int measureLatency() throws Exception { return XrayProcess.measureLatency(context(), outbound); }

        Choice(JSONObject node, String sourceId, String sourceLabel, int number, boolean used) throws Exception {
            this.sourceId = sourceId;
            this.sourceLabel = sourceLabel;
            this.number = number;
            this.used = used;
            this.outbound = outbound(node);
            id = node.getString("id");
            name = node.getString("name");
            protocol = node.getString("protocol");
        }
    }
    public static final class ImportResult {
        public final int imported, skipped;
        ImportResult(int imported, int skipped) { this.imported = imported; this.skipped = skipped; }
    }
    public static final class Source {
        public final String id, label;
        public final int count;
        Source(String id, String label, int count) { this.id = id; this.label = label; this.count = count; }
    }

    private static JSONObject store() throws Exception {
        File f = file();
        if (!f.exists()) return new JSONObject().put("sources", new JSONObject()).put("assigned", new JSONObject());
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(XrayProcess.readLimited(in, 24 * 1024 * 1024), StandardCharsets.UTF_8));
        }
    }
    private static void save(JSONObject doc) throws Exception {
        byte[] data = doc.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 24 * 1024 * 1024) throw new IOException("Node collection too large");
        File f = file();
        File tmp = File.createTempFile("proxy-nodes-", ".tmp", f.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
            Os.rename(tmp.getAbsolutePath(), f.getAbsolutePath());
        } finally { if (tmp.exists()) tmp.delete(); }
    }
    private static String digest(String s) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(hash, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    /** Called off the UI thread. No proxy node or subscription is sent to a conversion service. */
    public static ImportResult importSource(String editingId, String displayName, String source) throws Exception {
        String text = source.trim();
        byte[] data;
        String label = null;
        if (text.regionMatches(true, 0, "https://", 0, 8) || text.regionMatches(true, 0, "http://", 0, 7)) {
            URL url = new URL(text);
            label = url.getHost();
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            try {
                http.setConnectTimeout(12000);
                http.setReadTimeout(18000);
                http.setRequestProperty("User-Agent", "clash.meta (Mikan)");
                http.setUseCaches(false);
                int status = http.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                try (InputStream in = http.getInputStream()) { data = XrayProcess.readLimited(in, XrayProcess.MAX_INPUT); }
            } finally { http.disconnect(); }
        } else {
            data = text.getBytes(StandardCharsets.UTF_8);
        }
        JSONObject parsed = XrayProcess.parse(context(), data);
        JSONArray ns = parsed.getJSONArray("nodes");
        if (ns.length() == 0) throw new IOException("No supported nodes");
        String origin = digest(text);
        List<String> changed = new ArrayList<>();
        synchronized (ProxyConfig.class) {
            JSONObject doc = store();
            JSONObject sources = doc.getJSONObject("sources");
            JSONObject origins = object(doc, "origins");
            String sourceId = editingId;
            if (editingId != null && !sources.has(editingId)) throw new IOException("Subscription removed");
            for (Iterator<String> it = sources.keys(); it.hasNext();) {
                String id = it.next();
                if (origin.equals(origins.optString(id, id))) {
                    if (editingId != null && !editingId.equals(id)) throw new IOException("Subscription already exists");
                    sourceId = id;
                    break;
                }
            }
            if (sourceId == null) sourceId = sources.has(origin) ? UUID.randomUUID().toString() : origin;
            Map<String, Integer> occurrences = new HashMap<>();
            for (int i = 0; i < ns.length(); i++) {
                JSONObject node = ns.getJSONObject(i);
                String key = node.getString("protocol") + "\n" + node.getString("name");
                Integer previous = occurrences.get(key);
                int ordinal = previous == null ? 0 : previous;
                occurrences.put(key, ordinal + 1);
                node.put("id", digest(sourceId + "\n" + key + "\n" + ordinal));
            }
            replaceNodes(doc, sourceId, ns, changed);
            JSONObject labels = object(doc, "labels");
            String defaultLabel = label == null ? ns.getJSONObject(0).getString("name") : label;
            labels.put(sourceId, displayName.trim().isEmpty() ? labels.optString(sourceId, defaultLabel) : displayName.trim());
            origins.put(sourceId, origin);
            save(doc);
        }
        stopChanged(changed);
        return new ImportResult(ns.length(), parsed.optInt("skipped"));
    }

    private static JSONObject object(JSONObject doc, String name) throws Exception {
        JSONObject value = doc.optJSONObject(name);
        if (value == null) {
            value = new JSONObject();
            doc.put(name, value);
        }
        return value;
    }

    // Selections are snapshots for guest startup. Change them atomically with the catalog.
    private static void replaceNodes(JSONObject doc, String id, JSONArray nodes, List<String> changed) throws Exception {
        JSONObject sources = doc.getJSONObject("sources");
        JSONArray old = sources.optJSONArray(id);
        Set<String> oldIds = new HashSet<>();
        if (old != null) {
            for (int i = 0; i < old.length(); i++) oldIds.add(old.getJSONObject(i).getString("id"));
        }
        Map<String, JSONObject> updated = new HashMap<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            updated.put(node.getString("id"), node);
        }
        JSONObject assigned = doc.getJSONObject("assigned");
        for (Iterator<String> it = assigned.keys(); it.hasNext();) {
            String clone = it.next();
            JSONObject selected = assigned.optJSONObject(clone);
            if (selected == null) continue;
            String nodeId = selected.getString("id");
            JSONObject replacement = updated.get(nodeId);
            if (replacement != null) {
                if (!outbound(selected).equals(outbound(replacement))) changed.add(clone);
                assigned.put(clone, replacement);
            } else if (oldIds.contains(nodeId)) {
                assigned.put(clone, JSONObject.NULL);
                changed.add(clone);
            }
        }
        if (nodes.length() == 0) {
            sources.remove(id);
            object(doc, "labels").remove(id);
            object(doc, "origins").remove(id);
        } else {
            sources.put(id, nodes);
        }
    }

    private static void stopChanged(List<String> clones) {
        for (String clone : clones) {
            int split = clone.indexOf(':');
            BlackBoxCore.get().stopPackage(clone.substring(split + 1), Integer.parseInt(clone.substring(0, split)));
        }
    }

    private static String outbound(JSONObject node) throws Exception {
        return node.getJSONObject("outbound").toString();
    }

    private static String label(JSONObject doc, String id, JSONArray nodes) throws Exception {
        String fallback = nodes.getJSONObject(0).getString("name");
        JSONObject labels = doc.optJSONObject("labels");
        return labels == null ? fallback : labels.optString(id, fallback);
    }

    public static synchronized List<Source> sources() throws Exception {
        JSONObject doc = store();
        JSONObject sources = doc.getJSONObject("sources");
        List<Source> entries = new ArrayList<>();
        for (Iterator<String> it = sources.keys(); it.hasNext();) {
            String id = it.next();
            JSONArray nodes = sources.getJSONArray(id);
            entries.add(new Source(id, label(doc, id, nodes), nodes.length()));
        }
        return entries;
    }

    private static Set<String> usedByOtherClones(JSONObject doc, int user, String pkg) throws Exception {
        Set<String> used = new HashSet<>();
        JSONObject assigned = doc.getJSONObject("assigned");
        String current = key(user, pkg);
        for (Iterator<String> it = assigned.keys(); it.hasNext();) {
            String other = it.next();
            JSONObject node = assigned.optJSONObject(other);
            if (node != null && !current.equals(other) && other.endsWith(":" + pkg)) used.add(outbound(node));
        }
        return used;
    }

    private static List<Choice> choices(JSONObject doc, String sourceId, Set<String> used) throws Exception {
        JSONObject sources = doc.getJSONObject("sources");
        List<Choice> result = new ArrayList<>();
        for (Iterator<String> it = sources.keys(); it.hasNext();) {
            String id = it.next();
            if (sourceId != null && !sourceId.equals(id)) continue;
            JSONArray nodes = sources.getJSONArray(id);
            String label = label(doc, id, nodes);
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                result.add(new Choice(node, id, label, i + 1, !used.isEmpty() && used.contains(outbound(node))));
            }
        }
        return result;
    }

    public static synchronized List<Choice> nodes(String sourceId) throws Exception {
        return choices(store(), sourceId, java.util.Collections.emptySet());
    }

    public static synchronized List<Choice> choices(int user, String pkg) throws Exception {
        JSONObject doc = store();
        Set<String> used = usedByOtherClones(doc, user, pkg);
        List<Choice> result = choices(doc, null, used);
        JSONObject selected = doc.getJSONObject("assigned").optJSONObject(key(user, pkg));
        if (selected != null) {
            String id = selected.getString("id");
            for (Choice choice : result) if (id.equals(choice.id)) return result;
            // A 0.9.3 selection can outlive its original subscription.
            result.add(new Choice(selected, "", "", 0, used.contains(outbound(selected))));
        }
        return result;
    }

    public static synchronized void renameSource(String id, String label) throws Exception {
        JSONObject doc = store();
        if (!doc.getJSONObject("sources").has(id) || label.trim().isEmpty()) throw new IOException("Invalid subscription");
        object(doc, "labels").put(id, label.trim());
        save(doc);
    }

    public static void remove(String sourceId, String nodeId) throws Exception {
        List<String> changed = new ArrayList<>();
        synchronized (ProxyConfig.class) {
            JSONObject doc = store();
            JSONArray old = doc.getJSONObject("sources").getJSONArray(sourceId);
            JSONArray remaining = new JSONArray();
            if (nodeId != null) {
                for (int i = 0; i < old.length(); i++) {
                    JSONObject node = old.getJSONObject(i);
                    if (!nodeId.equals(node.getString("id"))) remaining.put(node);
                }
            }
            replaceNodes(doc, sourceId, remaining, changed);
            save(doc);
        }
        stopChanged(changed);
    }
    private static String key(int user, String pkg) { return user + ":" + pkg; }

    @SuppressWarnings("deprecation")
    private static String legacy(int user) {
        return context().getSharedPreferences("ProxyConfigs", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS)
                .getString("link_" + user, "");
    }
    public static synchronized String selectedId(int user, String pkg) throws Exception {
        JSONObject assigned = store().getJSONObject("assigned");
        String clone = key(user, pkg);
        JSONObject selected = assigned.optJSONObject(clone);
        if (selected != null) return selected.getString("id");
        return !assigned.has(clone) && !legacy(user).isEmpty() ? "legacy" : "";
    }
    /** Returns false when a second clone of this app must confirm sharing this endpoint. */
    public static synchronized boolean choose(int user, String pkg, String id, boolean allowShared) throws Exception {
        JSONObject doc = store();
        JSONObject assigned = doc.getJSONObject("assigned");
        String clone = key(user, pkg);
        if (id.isEmpty()) {
            assigned.put(clone, JSONObject.NULL);
        } else {
            JSONObject selected = assigned.optJSONObject(clone);
            if (selected != null && id.equals(selected.optString("id"))) return true;
            selected = null;
            JSONObject sources = doc.getJSONObject("sources");
            for (Iterator<String> it = sources.keys(); it.hasNext() && selected == null;) {
                JSONArray nodes = sources.getJSONArray(it.next());
                for (int i = 0; i < nodes.length(); i++) {
                    JSONObject candidate = nodes.getJSONObject(i);
                    if (id.equals(candidate.getString("id"))) { selected = candidate; break; }
                }
            }
            if (selected == null) throw new IOException("Node no longer available");
            if (!allowShared && usedByOtherClones(doc, user, pkg).contains(outbound(selected))) return false;
            assigned.put(clone, selected);
        }
        save(doc);
        return true;
    }
    public static synchronized void forget(int user, String pkg) throws Exception {
        JSONObject doc = store();
        if (doc.getJSONObject("assigned").remove(key(user, pkg)) != null) save(doc);
    }

    public static void applyToCurrentProcess(int user, String pkg) {
        try {
            JSONObject assigned = store().getJSONObject("assigned");
            String clone = key(user, pkg);
            JSONObject selected = assigned.optJSONObject(clone);
            if (selected == null && !assigned.has(clone)) {
                String link = legacy(user);
                if (!link.isEmpty()) {
                    JSONObject imported = XrayProcess.parse(context(), link.getBytes(StandardCharsets.UTF_8));
                    selected = imported.getJSONArray("nodes").getJSONObject(0);
                }
            }
            if (selected == null) {
                routesTcp = false;
                XrayProcess.stop();
                NativeCore.setProxy("", 0, "", "");
                return;
            }
            String password = UUID.randomUUID().toString();
            int port = XrayProcess.start(context(), selected.getJSONObject("outbound"), password);
            NativeCore.setProxy("127.0.0.1", port, "mikan", password);
            routesTcp = true;
            Slog.d("ProxyConfig", "Xray ready for user " + user + ", protocol " + selected.getString("protocol"));
        } catch (Exception e) {
            XrayProcess.stop();
            NativeCore.setProxy("", -1, "", "");
            routesTcp = true;
            Slog.e("ProxyConfig", "Failed to start the selected proxy; guest TCP is blocked");
        }
    }
}
