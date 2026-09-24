import simplecache.SimpleCacheFormat;
import simplecache.SimpleCacheLibrary;

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
 * Usage:
 *   javac -cp simplecache.jar -d . examples/LibraryTest.java
 *   java  -cp simplecache.jar:. LibraryTest &lt;cacheDir&gt; &lt;restoreOutDir&gt; [modifyOutDir]
 *
 *   cacheDir      a Simple Cache directory (HTTP cache or WebView code cache)
 *   restoreOutDir where restored *.js bodies are written
 *   modifyOutDir  optional; when given, the first .js entry is modified there
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
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            String key = e.keyString();
            if (key.endsWith(".js")) {
                Files.write(restoreDir.resolve(name(key)), e.body);
                js++;
            }
        }
        System.out.println("restore: entries=" + entries.size() + " js=" + js);

        if (workDir == null) {
            return;
        }

        // ---- modify the first JS entry ----
        SimpleCacheLibrary.RestoredEntry target = null;
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            if (e.keyString().endsWith(".js")) {
                target = e;
                break;
            }
        }
        if (target == null) {
            System.out.println("no .js entry to modify");
            return;
        }
        byte[] newBody = new String(target.body, StandardCharsets.UTF_8)
                .concat("\n/* MODIFIED VIA LIBRARY API */\n")
                .getBytes(StandardCharsets.UTF_8);

        long now = SimpleCacheFormat.fromUnixMillis(System.currentTimeMillis());
        SimpleCacheLibrary.ModifyResult mod = SimpleCacheLibrary.modify(
                indexBytes, readEntry(cacheDir, target.hash), newBody, now);

        Files.createDirectories(workDir.resolve("index-dir"));
        for (SimpleCacheLibrary.RestoredEntry e : entries) {
            Files.write(workDir.resolve(SimpleCacheLibrary.entryFileName(e.hash)),
                    readEntry(cacheDir, e.hash));
        }
        Files.write(workDir.resolve(SimpleCacheLibrary.entryFileName(mod.hash)), mod.entryFile);
        Files.write(workDir.resolve("index-dir").resolve("the-real-index"), mod.indexFile);
        Files.write(workDir.resolve("index"), SimpleCacheLibrary.buildFakeIndex());
        System.out.println("modify: " + SimpleCacheLibrary.entryFileName(mod.hash)
                + " body=" + newBody.length + " entryFile=" + mod.entryFile.length);

        // ---- re-restore from the written files ----
        byte[] newIndexBytes =
                Files.readAllBytes(workDir.resolve("index-dir").resolve("the-real-index"));
        List<SimpleCacheLibrary.RestoredEntry> after =
                SimpleCacheLibrary.restore(newIndexBytes, hash -> readEntry(workDir, hash));

        boolean bodyMatches = false;
        int problems = 0;
        for (SimpleCacheLibrary.RestoredEntry e : after) {
            if (e.hasProblems()) {
                problems++;
                System.out.println("  problem: " + e.keyString() + " " + e.warnings);
            }
            if (e.hash == mod.hash) {
                bodyMatches = java.util.Arrays.equals(e.body, newBody);
            }
        }
        System.out.println("re-restore: entries=" + after.size()
                + " modifiedBodyMatches=" + bodyMatches + " problems=" + problems);
        if (!bodyMatches || problems != 0) {
            throw new IllegalStateException("modify round trip failed");
        }
        System.out.println("LIBRARY TEST OK");
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
