package com.cfks.simplecache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory API for the Chromium Simple Cache (HTTP disk cache and WebView
 * V8 code cache).
 *
 * Every method takes and returns {@code byte[]}: the library never touches the
 * file system. Callers are responsible for reading/writing files.
 *
 * The cache is addressed through its index ({@code index-dir/the-real-index}),
 * which is the authoritative list of live entries. For each index entry the
 * caller reads the matching {@code <hash>_0} file (see {@link #entryFileName})
 * and hands the bytes to the library.
 *
 * Typical restore flow:
 * <pre>
 *   byte[] indexBytes = Files.readAllBytes(indexPath);
 *   List&lt;RestoredEntry&gt; files = SimpleCacheLibrary.restore(indexBytes,
 *       hash -&gt; {
 *         Path p = cacheDir.resolve(SimpleCacheLibrary.entryFileName(hash));
 *         return Files.exists(p) ? Files.readAllBytes(p) : null;
 *       });
 *   for (RestoredEntry f : files) {
 *     Files.write(outDir.resolve(nameFor(f.keyString())), f.body);
 *   }
 * </pre>
 *
 * Typical modify flow:
 * <pre>
 *   ModifyResult r = SimpleCacheLibrary.modify(indexBytes, entryBytes, newJs, now);
 *   Files.write(entryPath, r.entryFile);
 *   Files.write(indexPath, r.indexFile);
 * </pre>
 */
public final class SimpleCacheLibrary {

    private SimpleCacheLibrary() {}

    // ==================================================================
    // parse
    // ==================================================================

    /** Parses {@code index-dir/the-real-index}. */
    public static SimpleCacheIndex parseIndex(byte[] indexFileBytes) {
        return SimpleCacheIndex.parse(indexFileBytes, true);
    }

    /** Parses a {@code <hash>_0} entry file (streams 0 and 1). */
    public static SimpleCacheEntry parseEntry(byte[] entryFileBytes) {
        return SimpleCacheEntry.parse(entryFileBytes, true);
    }

    /** Filename of the entry file for an index hash, e.g. {@code "009e82c28a5a8532_0"}. */
    public static String entryFileName(long hash) {
        return SimpleCacheFormat.entryFileName(hash);
    }

    /** Entry hash (filename stem) for a raw key, e.g. a URL. */
    public static long entryHash(byte[] key) {
        return SimpleCacheFormat.entryHash(key);
    }

    /** Builds the 24 byte fake index ("index") for the current kSimpleVersion. */
    public static byte[] buildFakeIndex() {
        return SimpleCacheFormat.fakeIndexBytes();
    }

    /** Validates the fake index bytes and returns a short description. */
    public static String describeFakeIndex(byte[] fakeIndexBytes) {
        return SimpleCacheFormat.describeFakeIndex(fakeIndexBytes);
    }

    // ==================================================================
    // restore
    // ==================================================================

    /** Supplies the raw bytes of an entry file on demand. */
    @FunctionalInterface
    public interface EntryProvider {
        /** @return bytes of the {@code <hash>_0} file, or {@code null} if absent. */
        byte[] read(long hash);
    }

    /** One restored cache entry: its key plus stream 1 (body) and stream 0 (metadata). */
    public static final class RestoredEntry {
        public final long hash;
        public final byte[] key;
        public final byte[] body;      // stream 1
        public final byte[] metadata;  // stream 0
        public final SimpleCacheEntry entry;
        public final List<String> warnings;

        RestoredEntry(long hash, byte[] key, byte[] body, byte[] metadata,
                      SimpleCacheEntry entry, List<String> warnings) {
            this.hash = hash;
            this.key = key;
            this.body = body;
            this.metadata = metadata;
            this.entry = entry;
            this.warnings = warnings;
        }

        public String keyString() {
            return new String(key, StandardCharsets.UTF_8);
        }

        public boolean hasProblems() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Restores every live entry listed in the index. The order follows the
     * index; entries whose file is missing are returned with empty streams and
     * a warning.
     */
    public static List<RestoredEntry> restore(byte[] indexFileBytes, EntryProvider provider) {
        SimpleCacheIndex index = parseIndex(indexFileBytes);
        List<RestoredEntry> out = new ArrayList<>(index.entries.size());
        for (SimpleCacheIndex.Entry ie : index.entries) {
            byte[] raw = provider.read(ie.hash);
            if (raw == null) {
                out.add(new RestoredEntry(ie.hash, new byte[0], new byte[0], new byte[0],
                        null, Collections.singletonList("missing entry file")));
                continue;
            }
            SimpleCacheEntry e = parseEntry(raw);
            out.add(new RestoredEntry(ie.hash, e.key, e.stream1, e.stream0, e, e.warnings));
        }
        return out;
    }

    // ==================================================================
    // modify
    // ==================================================================

    /**
     * Replaces stream 1 (the response body) of an entry file and returns the
     * rewritten entry bytes. The key, header and stream 0 are preserved;
     * CRC32, key hash and the HTTP {@code content-length} are updated.
     */
    public static byte[] modifyEntryBody(byte[] entryFileBytes, byte[] newBody) {
        SimpleCacheEntry e = parseEntry(entryFileBytes);
        e.stream1 = newBody;
        e.stream0 = HttpCacheMetadata.patchContentLength(e.stream0, newBody.length);
        return e.serialize(e.eof0.hasKeySha256());
    }

    /**
     * Returns an updated index with the entry's size and last-used time
     * refreshed. The entry hash is derived from the key inside the entry.
     */
    public static byte[] updateIndex(byte[] indexFileBytes, byte[] newEntryFileBytes,
                                     long lastUsedInternal) {
        SimpleCacheIndex index = parseIndex(indexFileBytes);
        SimpleCacheEntry e = parseEntry(newEntryFileBytes);
        index.put(new SimpleCacheIndex.Entry(SimpleCacheFormat.entryHash(e.key),
                lastUsedInternal, newEntryFileBytes.length));
        index.recomputeCacheSize();
        index.reason = SimpleCacheIndex.REASON_IDLE;
        index.cacheLastModifiedInternal = lastUsedInternal;
        return index.serialize();
    }

    /** Result of {@link #modify}: rewritten entry and index bytes. */
    public static final class ModifyResult {
        public final long hash;
        public final byte[] entryFile;
        public final byte[] indexFile;

        ModifyResult(long hash, byte[] entryFile, byte[] indexFile) {
            this.hash = hash;
            this.entryFile = entryFile;
            this.indexFile = indexFile;
        }
    }

    /**
     * Convenience wrapper: rewrites the entry body and the index in one call.
     *
     * @param lastUsedInternal last-used time as a base::Time internal value
     *                         (microseconds since 1601); see
     *                         {@link SimpleCacheFormat#fromUnixMillis(long)}.
     */
    public static ModifyResult modify(byte[] indexFileBytes, byte[] entryFileBytes,
                                      byte[] newBody, long lastUsedInternal) {
        byte[] newEntry = modifyEntryBody(entryFileBytes, newBody);
        byte[] newIndex = updateIndex(indexFileBytes, newEntry, lastUsedInternal);
        long hash = SimpleCacheFormat.entryHash(parseEntry(newEntry).key);
        return new ModifyResult(hash, newEntry, newIndex);
    }
}
