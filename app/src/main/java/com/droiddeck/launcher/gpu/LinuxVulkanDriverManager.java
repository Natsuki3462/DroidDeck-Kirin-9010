package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LinuxVulkanDriverManager {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String DIR_NAME = "linux_vulkan_drivers";
    public static final String LIB_NAME = "libvulkan_freedreno.so";
    public static final String ICD_NAME = "icd.json";
    public static final String META_NAME = "meta.json";

    private final Context context;
    private final File rootDir;

    public LinuxVulkanDriverManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    public File getDriverDir(String id) {
        return new File(rootDir, id);
    }

    public boolean isInstalled(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return false;
        File dir = getDriverDir(id);
        if (!new File(dir, ICD_NAME).isFile()) return false;
        return libraryOf(dir) != null;
    }

    /**
     * The library an install's own manifest points at, if it is still there.
     *
     * The manifest is the source of truth rather than a fixed file name: a pack may ship
     * `libvulkan_lvp.so` or any other name it declared, and only the manifest knows which.
     */
    static File libraryOf(File dir) {
        try {
            JSONObject body = new JSONObject(FileUtils.readString(new File(dir, ICD_NAME))).optJSONObject("ICD");
            String path = body != null ? body.optString("library_path", "") : "";
            if (!path.isEmpty()) {
                File lib = new File(path);
                if (!lib.isAbsolute()) lib = new File(dir, path);
                if (lib.isFile()) return lib;
            }
        } catch (Exception ignored) {
            // Fall through to the historical name below.
        }
        // Installs made before packs could name their entry point keep the fixed name.
        File legacy = new File(dir, LIB_NAME);
        return legacy.isFile() ? legacy : null;
    }

    public String getIcdPath(String id) {
        return isInstalled(id) ? new File(getDriverDir(id), ICD_NAME).getAbsolutePath() : null;
    }

    public List<String> enumerateInstalledDrivers() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) if (d.isDirectory() && isInstalled(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    private JSONObject readMeta(String id) {
        try {
            File meta = new File(getDriverDir(id), META_NAME);
            if (!meta.isFile()) return null;
            return new JSONObject(FileUtils.readString(meta));
        } catch (Exception e) {
            return null;
        }
    }

    public String getDriverName(String id) {
        JSONObject m = readMeta(id);
        String name = m != null ? m.optString("name", "") : "";
        return name.isEmpty() ? id : name;
    }

    public String getDriverVersion(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("driverVersion", "") : "";
    }

    public String getMinGlibc(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("minGlibc", "") : "";
    }

    public void removeDriver(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return;
        Log.d(TAG, "removing imported Linux Vulkan driver " + id);
        FileUtils.delete(getDriverDir(id));
    }

    public String installDriver(Uri zipUri, String displayName) throws IOException {
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            JSONObject zipMeta = null;
            // Every shared object in the pack is kept, not just the entry point. A GPU driver pack is
            // one file (Turnip), but a CPU renderer is not: lavapipe needs its LLVM and its X/Wayland
            // shims beside it, and the loader has to find them in the same directory.
            Map<String, File> libs = new LinkedHashMap<>();
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("cannot open " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    if (base.equals(META_NAME)) {
                        try {
                            zipMeta = new JSONObject(new String(readAll(zis), StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            Log.w(TAG, "meta.json unreadable, ignoring: " + e.getMessage());
                        }
                    } else if (base.endsWith(".so") || base.contains(".so.")) {
                        File dst = new File(tmpDir, base);
                        Files.copy(zis, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        libs.put(base, dst);
                    }
                }
            }
            String declared = zipMeta != null ? zipMeta.optString("library", "") : "";
            String entryName = pickEntry(libs, declared);
            File entryFile = entryName == null ? null : libs.get(entryName);
            // Packs that do not name their entry point keep the historical file name, so an existing
            // Turnip install and a fresh one are laid out the same way.
            if (entryFile != null && declared.isEmpty() && !entryName.equals(LIB_NAME)) {
                File renamed = new File(tmpDir, LIB_NAME);
                if (entryFile.renameTo(renamed)) entryFile = renamed;
            }
            String id = adopt(tmpDir, entryFile, entryName, zipMeta, displayName);
            keep = true;
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    File newStagingDir() throws IOException {
        File tmpDir = new File(rootDir, ".tmp-" + System.nanoTime());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        return tmpDir;
    }

    String adopt(File tmpDir, File so, String soName, JSONObject zipMeta, String displayName) throws IOException {
        try {
            if (so == null || !so.isFile()) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_no_library));
            }
            if (!isAarch64Elf(so)) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_not_aarch64, soName));
            }
            if (!containsAscii(so, "libc.so.6")) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_not_glibc, soName));
            }
            String kind = zipMeta != null ? zipMeta.optString("kind", "") : "";
            if (!kind.isEmpty() && !"linux-vulkan-icd".equals(kind)) {
                Log.w(TAG, "meta.json says kind=" + kind + ", importing anyway (the binary is glibc)");
            }

            String name = zipMeta != null ? zipMeta.optString("name", "") : "";
            String version = zipMeta != null ? zipMeta.optString("driverVersion", "") : "";
            String minGlibc = zipMeta != null ? zipMeta.optString("minGlibc", "") : "";
            if (name.isEmpty()) {
                name = displayName != null ? displayName : "linux-driver";
                if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String id = uniqueId(sanitizeId(name));
            File dir = getDriverDir(id);

            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject icdBody = new JSONObject();
            icdBody.put("library_path", new File(dir, so.getName()).getAbsolutePath());
            icdBody.put("api_version", apiVersion(zipMeta != null ? zipMeta.optString("apiVersion", "") : ""));
            icd.put("ICD", icdBody);
            if (!FileUtils.writeString(new File(tmpDir, ICD_NAME), icd.toString(2))) throw new IOException("cannot write icd.json");

            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("name", name);
            meta.put("driverVersion", version);
            meta.put("libc", "glibc");
            meta.put("minGlibc", minGlibc);
            meta.put("sourceLibraryName", soName);
            meta.put("importedAt", System.currentTimeMillis());
            if (!FileUtils.writeString(new File(tmpDir, META_NAME), meta.toString(2))) throw new IOException("cannot write meta.json");

            if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
            Log.i(TAG, "imported Linux Vulkan driver " + id + " (" + soName + ", minGlibc=" + minGlibc + ") -> " + dir);
            return id;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (getDriverDir(id).exists()) id = base + "-" + (n++);
        return id;
    }

    /**
     * Which library in the pack the loader should open.
     *
     * A pack may name it in meta.json; otherwise the historical Turnip name is used, and failing
     * that any Vulkan driver in the pack - so a CPU renderer can be imported without a meta.json.
     */
    /**
     * The Vulkan version to declare in the generated manifest.
     *
     * This used to be the constant 1.1.274, which was true of every Turnip pack and is not true of
     * a CPU renderer: a Mesa 26 lavapipe is a 1.4 driver, and a manifest that says 1.1 makes the
     * loader treat it as one, so a compositor that needs 1.3 reports "no physical device" even
     * though the driver is loaded and enumerable. The pack states its own version; the old constant
     * stays as the default so packs published before this field still import unchanged.
     */
    static String apiVersion(String declared) {
        String value = declared == null ? "" : declared.trim();
        return value.matches("\\d+\\.\\d+\\.\\d+") ? value : DEFAULT_API_VERSION;
    }

    static final String DEFAULT_API_VERSION = "1.1.274";

    static String pickEntry(Map<String, File> libs, String declared) {
        if (!declared.isEmpty()) return libs.containsKey(declared) ? declared : null;
        for (String name : libs.keySet()) if (name.startsWith("libvulkan_freedreno")) return name;
        for (String name : libs.keySet()) if (name.startsWith("libvulkan_")) return name;
        return null;
    }

    static String sanitizeId(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "linux-driver" : s;
    }

    static boolean isAarch64Elf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 20) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            if (h[4] != 2 || h[5] != 1) return false;
            int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
            return machine == 0xB7;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    static boolean containsAscii(File f, String needle) {
        byte[] nb = needle.getBytes(StandardCharsets.US_ASCII);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int len = carry + r;
                for (int i = 0; i + nb.length <= len; i++) {
                    int j = 0;
                    while (j < nb.length && buf[i + j] == nb[j]) j++;
                    if (j == nb.length) return true;
                }
                carry = Math.min(nb.length - 1, len);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
