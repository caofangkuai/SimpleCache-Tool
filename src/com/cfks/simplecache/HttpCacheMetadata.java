package com.cfks.simplecache;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for the persisted {@code HttpResponseInfo} pickle stored in stream 0
 * of an HTTP disk cache entry.
 *
 * Only the parts needed to keep the metadata consistent when the response body
 * (stream 1) is replaced are implemented: locating the persisted header block
 * and rewriting its {@code content-length} value, including the surrounding
 * pickle length fields.
 *
 * The header block is a NUL separated list of lines ("HTTP/1.1 200", then
 * "name:value" lines) stored as a length prefixed string inside the pickle.
 */
public final class HttpCacheMetadata {

    private HttpCacheMetadata() {}

    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("(?i)(?:^|\\x00)content-length:(\\d+)");

    /** Value of the content-length header in stream 0, or -1 when absent. */
    public static int contentLength(byte[] stream0) {
        String block = headerBlock(stream0);
        if (block == null) {
            return -1;
        }
        Matcher m = CONTENT_LENGTH.matcher(block);
        if (!m.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Value of the {@code content-encoding} header in stream 0, or null. */
    public static String contentEncoding(byte[] stream0) {
        String value = headerValue(stream0, "content-encoding");
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * Value of the named header in the persisted HTTP header block, or null.
     * Header names are matched case insensitively.
     */
    public static String headerValue(byte[] stream0, String name) {
        String block = headerBlock(stream0);
        if (block == null) {
            return null;
        }
        String needle = name.toLowerCase(Locale.ROOT) + ":";
        String lower = block.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(needle);
        while (idx > 0 && block.charAt(idx - 1) != '\0') {
            idx = lower.indexOf(needle, idx + 1);
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        int end = block.indexOf('\0', start);
        if (end < 0) {
            end = block.length();
        }
        return block.substring(start, end).trim();
    }

    /**
     * Returns a copy of stream 0 whose content-length header matches
     * {@code newLength}. When stream 0 has no persisted HTTP header block (for
     * example a WebView code cache entry) the input is returned unchanged.
     */
    public static byte[] patchContentLength(byte[] stream0, int newLength) {
        int hs = headerOffset(stream0);
        if (hs < 0) {
            return stream0;
        }
        int hlen = SimpleCacheFormat.u32(stream0, hs - 4);
        byte[] block = SimpleCacheFormat.slice(stream0, hs, hlen);
        byte[] newBlock = replaceContentLength(block, Integer.toString(newLength));
        if (newBlock == block) {
            return stream0;
        }
        int alignedEnd = align4(hs + hlen);
        byte[] head = SimpleCacheFormat.slice(stream0, 0, hs - 4);
        byte[] rest = SimpleCacheFormat.slice(stream0, alignedEnd, stream0.length - alignedEnd);
        int pad = (4 - ((hs + newBlock.length) & 3)) & 3;
        byte[] lenBytes = new byte[4];
        SimpleCacheFormat.putU32(lenBytes, 0, newBlock.length);
        byte[] out = SimpleCacheFormat.concat(head, lenBytes, newBlock, new byte[pad], rest);
        SimpleCacheFormat.putU32(out, 0, out.length - 4);
        return out;
    }

    private static String headerBlock(byte[] stream0) {
        int hs = headerOffset(stream0);
        if (hs < 0) {
            return null;
        }
        int hlen = SimpleCacheFormat.u32(stream0, hs - 4);
        return new String(stream0, hs, hlen, StandardCharsets.ISO_8859_1);
    }

    /** Offset of the "HTTP/1." status line inside stream 0, or -1. */
    private static int headerOffset(byte[] stream0) {
        if (stream0 == null || stream0.length < 8) {
            return -1;
        }
        int hs = indexOf(stream0, "HTTP/1.", 0);
        if (hs < 4) {
            return -1;
        }
        int hlen = SimpleCacheFormat.u32(stream0, hs - 4);
        if (hlen <= 0 || hs + hlen > stream0.length) {
            return -1;
        }
        return hs;
    }

    private static byte[] replaceContentLength(byte[] block, String value) {
        String s = new String(block, StandardCharsets.ISO_8859_1);
        String lower = s.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("content-length:");
        while (idx > 0 && s.charAt(idx - 1) != '\0') {
            idx = lower.indexOf("content-length:", idx + 1);
        }
        if (idx < 0) {
            return block;
        }
        int colon = idx + "content-length".length();
        int end = s.indexOf('\0', colon);
        if (end < 0) {
            end = s.length();
        }
        String replaced = s.substring(0, colon + 1) + value + s.substring(end);
        return replaced.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int align4(int n) {
        return (n + 3) & ~3;
    }

    private static int indexOf(byte[] haystack, String needle, int from) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = from; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
