package com.cfks.simplecache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decoding and encoding of HTTP {@code content-encoding} values found in a
 * Simple Cache entry.
 *
 * The cache stores the response body exactly as it arrived on the wire, so an
 * entry carrying {@code content-encoding: gzip} holds gzip compressed bytes.
 * {@link SimpleCacheLibrary} uses this helper to transparently decode bodies on
 * restore and to re-encode them on modify.
 *
 * Only {@code gzip} and {@code deflate} (zlib, RFC 1950) are supported. Other
 * encodings such as {@code br} are left untouched and reported as unsupported
 * through {@link #isSupported(String)}.
 */
public final class ContentEncoding {

    public static final String GZIP = "gzip";
    public static final String DEFLATE = "deflate";
    public static final String IDENTITY = "identity";

    private ContentEncoding() {}

    /** Normalizes a header value: trims, lowercases and drops parameters. */
    public static String normalize(String encoding) {
        if (encoding == null) {
            return null;
        }
        String e = encoding.trim().toLowerCase(Locale.ROOT);
        int semi = e.indexOf(';');
        if (semi >= 0) {
            e = e.substring(0, semi).trim();
        }
        return e.isEmpty() ? null : e;
    }

    /** True when this class can both decode and encode the given encoding. */
    public static boolean isSupported(String encoding) {
        String e = normalize(encoding);
        return GZIP.equals(e) || DEFLATE.equals(e);
    }

    /** True when the given encoding compresses the payload. */
    public static boolean isCompressed(String encoding) {
        return isSupported(encoding);
    }

    /**
     * Decodes {@code data} according to {@code encoding}. Identity and
     * unsupported encodings return the input unchanged; a malformed stream
     * returns {@code null} so the caller can fall back to the raw bytes.
     */
    public static byte[] decode(byte[] data, String encoding) {
        String e = normalize(encoding);
        try {
            if (GZIP.equals(e)) {
                return readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
            }
            if (DEFLATE.equals(e)) {
                return readAll(new InflaterInputStream(new ByteArrayInputStream(data),
                        new Inflater()));
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        return data;
    }

    /**
     * Encodes {@code data} according to {@code encoding}. Identity and
     * unsupported encodings return the input unchanged; an encoding failure
     * returns {@code null}.
     */
    public static byte[] encode(byte[] data, String encoding) {
        String e = normalize(encoding);
        try {
            if (GZIP.equals(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                    gz.write(data);
                }
                return out.toByteArray();
            }
            if (DEFLATE.equals(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DeflaterOutputStream df = new DeflaterOutputStream(out, new Deflater())) {
                    df.write(data);
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        return data;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
