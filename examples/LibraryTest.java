import com.cfks.simplecache.SimpleCacheFormat;
import com.cfks.simplecache.SimpleCacheLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Example / smoke test for the Simple Cache library.
 *
 * Demonstrates that the library itself performs no file I/O: this class reads
 * the index and entry files, calls the library with byte[], and writes results.
 *
 * Restore transparently decodes gzip/deflate bodies; modify re-encodes the
 * replacement body to match the entry's content-encoding.
 *
 * Usage:
 *   javac -cp simplecache.jar -d . examples/LibraryTest.java
 *   java  -cp simplecache.jar:. LibraryTest &lt;cacheDir&gt; &lt;restoreOutDir&gt; [modifyOutDir]
 *
 *   cacheDir      a Simple Cache directory (HTTP cache or WebView code cache)
 *   restoreOutDir where restored *.js bodies are written
 *   modifyOutDir  optional; when given, a .js entry and a compressed entry are
 *                 modified there and re-restored to verify the round trip
 */
public class LibraryTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "usage: LibraryTest <cacheDir> <restoreOutDir> [modifyOutDir]");
            System.exit(2);
        }
        Path cacheDir = Paths.get(args[0]);
        Path restoreDir = Paths.get(args[1]);
        Path workDir = args.length > 2 ? Paths.get(args[2]) : null;

        byte[] indexBytes = Files.readAllBytes(
                cacheDir.resolve("index-dir").resolve("the-real-index"));

        // ---- parse + restore, driven purely by the index ----
        List<SimpleCacheLibrary.RestoredEntry> entries =
                SimpleCacheLibrary.restore(indexBytes, hash -> readEntry(cacheDir, hash));

        Files.createDirectories(restoreDir);
        int js = 0;
        int encoded = 0;
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            if (e.contentEncoding != null) {
                encoded++;
            }
            String key = e.keyString();
            if (key.endsWith(".js")) {
                Files.write(restoreDir.resolve(name(key)), e.body);
                js++;
            }
        }
        System.out.println("restore: entries=" + entries.size() + " js=" + js
                + " encoded=" + encoded);

        if (workDir == null) {
            return;
        }

        // ---- modify a plain .js entry and a content-encoded entry ----
        SimpleCacheLibrary.RestoredEntry jsEntry = firstEndingWith(entries, ".js");
        SimpleCacheLibrary.RestoredEntry gzEntry = firstEncoded(entries);

        long now = SimpleCacheFormat.fromUnixMillis(System.currentTimeMillis());
        byte[] newJs = null;
        byte[] newGz = null;
        byte[] currentIndex = indexBytes;
        SimpleCacheLibrary.ModifyResult modJs = null;
        SimpleCacheLibrary.ModifyResult modGz = null;

        if (jsEntry != null) {
            newJs = new String(jsEntry.body, StandardCharsets.UTF_8)
                    .concat("\n/* MODIFIED VIA LIBRARY API */\n")
                    .getBytes(StandardCharsets.UTF_8);
            modJs = SimpleCacheLibrary.modify(
                    currentIndex, readEntry(cacheDir, jsEntry.hash), newJs, now);
            currentIndex = modJs.indexFile;
        }
        if (gzEntry != null) {
            newGz = new String(gzEntry.body, StandardCharsets.UTF_8)
                    .concat("\n/* GZIP ROUND TRIP */\n")
                    .getBytes(StandardCharsets.UTF_8);
            modGz = SimpleCacheLibrary.modify(
                    currentIndex, readEntry(cacheDir, gzEntry.hash), newGz, now);
            currentIndex = modGz.indexFile;
        }

        Files.createDirectories(workDir.resolve("index-dir"));
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            Files.write(workDir.resolve(SimpleCacheLibrary.entryFileName(e.hash)),
                    readEntry(cacheDir, e.hash));
        }
        if (modJs != null) {
            Files.write(workDir.resolve(SimpleCacheLibrary.entryFileName(modJs.hash)),
                    modJs.entryFile);
        }
        if (modGz != null) {
            Files.write(workDir.resolve(SimpleCacheLibrary.entryFileName(modGz.hash)),
                    modGz.entryFile);
        }
        Files.write(workDir.resolve("index-dir").resolve("the-real-index"), currentIndex);
        Files.write(workDir.resolve("index"), SimpleCacheLibrary.buildFakeIndex());
        System.out.println("modify: js=" + describe(modJs) + " gzip=" + describe(modGz));

        // ---- re-restore from the written files ----
        byte[] newIndexBytes =
                Files.readAllBytes(workDir.resolve("index-dir").resolve("the-real-index"));
        List<SimpleCacheLibrary.RestoredEntry> after =
                SimpleCacheLibrary.restore(newIndexBytes, hash -> readEntry(workDir, hash));

        boolean jsMatches = true;
        boolean gzMatches = true;
        int problems = 0;
        for (SimpleCacheLibrary.RestoredEntry e : after) {
            if (e.hasProblems()) {
                problems++;
                System.out.println("  problem: " + e.keyString() + " " + e.warnings);
            }
            if (modJs != null && e.hash == modJs.hash) {
                jsMatches = java.util.Arrays.equals(e.body, newJs);
            }
            if (modGz != null && e.hash == modGz.hash) {
                gzMatches = java.util.Arrays.equals(e.body, newGz)
                        && e.contentEncoding != null
                        && !java.util.Arrays.equals(e.body, e.rawBody);
            }
        }
        System.out.println("re-restore: entries=" + after.size()
                + " jsMatches=" + jsMatches + " gzMatches=" + gzMatches
                + " problems=" + problems);
        if (!jsMatches || !gzMatches || problems != 0) {
            throw new IllegalStateException("modify round trip failed");
        }
        System.out.println("LIBRARY TEST OK");
    }

    private static SimpleCacheLibrary.RestoredEntry firstEndingWith(
            List<SimpleCacheLibrary.RestoredEntry> entries, String suffix) {
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            if (e.keyString().endsWith(suffix)) {
                return e;
            }
        }
        return null;
    }

    private static SimpleCacheLibrary.RestoredEntry firstEncoded(
            List<SimpleCacheLibrary.RestoredEntry> entries) {
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            if (e.contentEncoding != null) {
                return e;
            }
        }
        return null;
    }

    private static String describe(SimpleCacheLibrary.ModifyResult mod) {
        return mod == null ? "-" : SimpleCacheLibrary.entryFileName(mod.hash)
                + "(" + mod.entryFile.length + ")";
    }

    private static byte[] readEntry(Path cacheDir, long hash) {
        try {
            Path p = cacheDir.resolve(SimpleCacheLibrary.entryFileName(hash));
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String name(String key) {
        String k = key.split("\\?")[0];
        int slash = k.lastIndexOf('/');
        return slash >= 0 ? k.substring(slash + 1) : k;
    }
}
