package com.cfks.simplecache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec for the WebView / Chromium "generated code cache" layer that is stored
 * on top of Simple Cache (content/browser/code_cache/generated_code_cache.cc).
 *
 * Cache key:
 *   {@code "_key" + url + " \n" + contextKey}   (contextKey may be empty)
 *
 * stream 0 layout:
 *   [response_time : int64 LE, micros since Windows epoch]
 *   [data_size     : uint32 LE]
 *   [inline payload | checksum key | nothing]
 *
 * Three variants:
 *   inline     : data_size &lt;= 4096   -&gt; payload stored in stream 0, stream 1 empty
 *   dedicated  : data_size &lt;= 16384  -&gt; stream 0 is only the 12 byte header,
 *                                       payload stored in stream 1
 *   indirect   : data_size &gt;  16384  -&gt; stream 0 = header + 64 char SHA-256 hex,
 *                                       stream 1 empty, payload lives in a second
 *                                       entry keyed by the checksum string
 */
public final class CodeCache {

    public static final String PREFIX = "_key";
    public static final String SEPARATOR = " \n";
    public static final int HEADER_SIZE = 12;
    public static final int INLINE_LIMIT = 4096;
    public static final int DEDICATED_LIMIT = 16384;
    public static final int SHA_KEY_LENGTH = 64;

    public enum Kind {
        INLINE, DEDICATED, INDIRECT, SHA_DEDUP, EMPTY, UNKNOWN
    }

    public static final class Decoded {
        public Kind kind = Kind.UNKNOWN;
        public long responseTimeInternal;
        public int dataSize;
        public byte[] payload;       // resolved data (may be null for indirect)
        public String checksumKey;   // for indirect
        public String description() {
            return kind + (checksumKey != null ? " -> " + checksumKey : "");
        }
    }

    private CodeCache() {}

    // ------------------------------------------------------------------
    // key helpers
    // ------------------------------------------------------------------

    public static String resourceKey(String url) {
        return PREFIX + url;
    }

    public static String cacheKey(String url) {
        return cacheKey(url, "");
    }

    public static String cacheKey(String url, String contextKey) {
        return resourceKey(url) + SEPARATOR + (contextKey == null ? "" : contextKey);
    }

    /** Extracts the resource URL from a code cache key, or null. */
    public static String urlFromKey(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        int sep = key.indexOf(SEPARATOR);
        if (sep < PREFIX.length()) {
            return null;
        }
        return key.substring(PREFIX.length(), sep);
    }

    // ------------------------------------------------------------------
    // decode
    // ------------------------------------------------------------------

    public static Decoded decode(byte[] stream0, byte[] stream1) {
        Decoded d = new Decoded();
        byte[] s0 = stream0 == null ? new byte[0] : stream0;
        byte[] s1 = stream1 == null ? new byte[0] : stream1;

        if (s0.length == 0) {
            if (s1.length > 0) {
                d.kind = Kind.SHA_DEDUP;
                d.dataSize = s1.length;
                d.payload = s1;
                return d;
            }
            d.kind = Kind.EMPTY;
            return d;
        }
        if (s0.length < HEADER_SIZE) {
            return d;
        }
        d.responseTimeInternal = SimpleCacheFormat.u64(s0, 0);
        d.dataSize = SimpleCacheFormat.u32(s0, 8);
        int rest = s0.length - HEADER_SIZE;
        byte[] tail = SimpleCacheFormat.slice(s0, HEADER_SIZE, rest);

        if (s1.length > 0) {
            d.kind = Kind.DEDICATED;
            d.payload = s1;
        } else if (rest == d.dataSize && d.dataSize > 0) {
            d.kind = Kind.INLINE;
            d.payload = tail;
        } else if (rest == SHA_KEY_LENGTH) {
            d.kind = Kind.INDIRECT;
            d.checksumKey = new String(tail, StandardCharsets.US_ASCII);
        } else if (rest == 0 && d.dataSize == 0) {
            d.kind = Kind.EMPTY;
        } else {
            d.kind = Kind.UNKNOWN;
        }
        return d;
    }

    // ------------------------------------------------------------------
    // encode
    // ------------------------------------------------------------------

    public static final class Built {
        /** Primary entry (keyed by the code cache key). */
        public SimpleCacheEntry primary;
        /** Optional de-duplicated payload entry (keyed by the SHA-256 hex). */
        public SimpleCacheEntry dedup;
    }

    /**
     * Builds the entry (or entries) that reproduce the code cache layout for the
     * supplied raw payload.
     *
     * @param url           resource URL
     * @param data          raw cached data (for the real browser this is V8
     *                      CachedData; for format experiments any bytes work)
     * @param responseTime  base::Time internal value (micros since 1601)
     * @param uppercaseSha  whether the checksum key uses uppercase hex (matches
     *                      the sample cache)
     */
    public static Built build(String url, byte[] data, long responseTime, boolean uppercaseSha) {
        Built b = new Built();
        String key = cacheKey(url);
        int size = data.length;
        byte[] header = header(responseTime, size);

        if (size <= INLINE_LIMIT) {
            SimpleCacheEntry e = new SimpleCacheEntry();
            e.key = SimpleCacheFormat.ascii(key);
            e.stream0 = SimpleCacheFormat.concat(header, data);
            e.stream1 = new byte[0];
            b.primary = e;
        } else if (size <= DEDICATED_LIMIT) {
            SimpleCacheEntry e = new SimpleCacheEntry();
            e.key = SimpleCacheFormat.ascii(key);
            e.stream0 = header;
            e.stream1 = data;
            b.primary = e;
        } else {
            String checksum = SimpleCacheFormat.hex(SimpleCacheFormat.sha256(data));
            if (uppercaseSha) {
                checksum = checksum.toUpperCase();
            }
            SimpleCacheEntry small = new SimpleCacheEntry();
            small.key = SimpleCacheFormat.ascii(key);
            small.stream0 = SimpleCacheFormat.concat(header, SimpleCacheFormat.ascii(checksum));
            small.stream1 = new byte[0];

            SimpleCacheEntry big = new SimpleCacheEntry();
            big.key = SimpleCacheFormat.ascii(checksum);
            big.stream0 = new byte[0];
            big.stream1 = data;

            b.primary = small;
            b.dedup = big;
        }
        return b;
    }

    public static byte[] header(long responseTimeInternal, int dataSize) {
        byte[] h = new byte[HEADER_SIZE];
        SimpleCacheFormat.putU64(h, 0, responseTimeInternal);
        SimpleCacheFormat.putU32(h, 8, dataSize);
        return h;
    }

    /** Convenience: build only the primary entry for a generic simple cache. */
    public static SimpleCacheEntry buildSimple(String rawKey, byte[] body) {
        SimpleCacheEntry e = new SimpleCacheEntry();
        e.key = SimpleCacheFormat.ascii(rawKey);
        e.stream0 = new byte[0];
        e.stream1 = body;
        return e;
    }

    /** Returns a copy of the list of all entries produced by a build. */
    public static List<SimpleCacheEntry> allEntries(Built b) {
        List<SimpleCacheEntry> list = new ArrayList<>();
        if (b.primary != null) {
            list.add(b.primary);
        }
        if (b.dedup != null) {
            list.add(b.dedup);
        }
        return list;
    }
}
