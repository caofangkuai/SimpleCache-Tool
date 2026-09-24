package simplecache;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Low level constants and helpers for the Chromium Simple Cache on-disk format.
 *
 * Reference: net/disk_cache/simple/simple_entry_format.h,
 * net/disk_cache/simple/simple_util.cc,
 * base/third_party/superfasthash/superfasthash.c
 */
public final class SimpleCacheFormat {

    private SimpleCacheFormat() {}

    // ---- magic numbers (simple_entry_format.h) ----
    public static final long INITIAL_MAGIC = 0xfcfb6d1ba7725c30L;
    public static final long FINAL_MAGIC = 0xf4fa6f45970d41d8L;
    public static final long SPARSE_RANGE_MAGIC = 0xeb97bf016553676bL;
    public static final long INDEX_MAGIC = 0x656e74657220796fL;

    // ---- struct sizes ----
    public static final int HEADER_SIZE = 24; // SimpleFileHeader incl. explicit padding
    public static final int EOF_SIZE = 24;    // SimpleFileEOF incl. explicit padding
    public static final int SHA256_SIZE = 32;
    public static final int INDEX_HEADER_SIZE = 8; // base::Pickle header (payload_size + crc)

    // ---- versions (simple_backend_version.h) ----
    public static final int ENTRY_VERSION = 5;       // kSimpleEntryVersionOnDisk
    public static final int SPARSE_VERSION = 9;      // kSimpleSparseEntryVersion
    public static final int INDEX_FILE_VERSION = 9;  // kSimpleIndexFileVersion
    public static final int MIN_INDEX_FILE_VERSION = 8;
    public static final int SIMPLE_VERSION = 9;      // kSimpleVersion (fake index "index")

    // ---- fake index ("index") file name (simple_version_upgrade.cc) ----
    public static final String FAKE_INDEX_FILE = "index";
    public static final String INDEX_DIR = "index-dir";
    public static final String REAL_INDEX_FILE = "the-real-index";

    // ---- SimpleFileEOF flags ----
    public static final int FLAG_HAS_CRC32 = 1;
    public static final int FLAG_HAS_KEY_SHA256 = 2;

    // ================= little endian primitives =================

    public static int u32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    public static long u64(byte[] b, int off) {
        return (b[off] & 0xffL)
                | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16)
                | ((b[off + 3] & 0xffL) << 24)
                | ((b[off + 4] & 0xffL) << 32)
                | ((b[off + 5] & 0xffL) << 40)
                | ((b[off + 6] & 0xffL) << 48)
                | ((b[off + 7] & 0xffL) << 56);
    }

    public static long i64(byte[] b, int off) {
        return u64(b, off);
    }

    public static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    public static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (8 * i));
        }
    }

    // ================= hashes =================

    public static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    public static int crc32(byte[] data, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, off, len);
        return (int) crc.getValue();
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static byte[] digest(String algo, byte[] data) {
        try {
            return MessageDigest.getInstance(algo).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Filename hash: SHA1(key) first 8 bytes interpreted as a little endian
     * uint64, printed as %016x. (simple_util::GetEntryHashKey)
     */
    public static long entryHash(byte[] key) {
        byte[] h = sha1(key);
        return u64(h, 0);
    }

    public static String entryHashHex(byte[] key) {
        return String.format("%016x", entryHash(key));
    }

    /** Filename of the stream 0/1 entry file for a 64 bit entry hash. */
    public static String entryFileName(long hash) {
        return String.format("%016x_0", hash);
    }

    /**
     * Header key_hash = base::PersistentHash = Paul Hsieh's SuperFastHash.
     */
    public static int keyHash(byte[] data) {
        int len = data.length;
        if (len <= 0) {
            return 0;
        }
        int hash = len;
        int rem = len & 3;
        int n = len >>> 2;
        int i = 0;
        for (int k = 0; k < n; k++) {
            hash += get16(data, i);
            int tmp = (get16(data, i + 2) << 11) ^ hash;
            hash = (hash << 16) ^ tmp;
            i += 4;
            hash += (hash >>> 11);
        }
        switch (rem) {
            case 3:
                hash += get16(data, i);
                hash ^= (hash << 16);
                hash ^= ((int) (byte) data[i + 2]) << 18;
                hash += (hash >>> 11);
                break;
            case 2:
                hash += get16(data, i);
                hash ^= (hash << 11);
                hash += (hash >>> 17);
                break;
            case 1:
                hash += (int) (byte) data[i];
                hash ^= (hash << 10);
                hash += (hash >>> 1);
                break;
            default:
                break;
        }
        hash ^= (hash << 3);
        hash += (hash >>> 5);
        hash ^= (hash << 4);
        hash += (hash >>> 17);
        hash ^= (hash << 25);
        hash += (hash >>> 6);
        return hash;
    }

    private static int get16(byte[] d, int p) {
        return (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8);
    }

    // ================= hex =================

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xff;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0xf]);
        }
        return sb.toString();
    }

    public static String hexUpper(byte[] b) {
        return hex(b).toUpperCase();
    }

    // ================= misc =================

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    public static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The fake index ("index") written by
     * disk_cache::WriteFakeIndexFile (simple_version_upgrade.cc).
     * FakeIndexData layout: u64 magic, u32 version, u32 zero, u32 zero2,
     * u32 encryption_status (24 bytes, no padding).
     */
    public static byte[] fakeIndexBytes() {
        byte[] b = new byte[24];
        putU64(b, 0, INITIAL_MAGIC);
        putU32(b, 8, SIMPLE_VERSION);
        putU32(b, 12, 0);
        putU32(b, 16, 0);
        putU32(b, 20, 0);
        return b;
    }

    /**
     * Parses the fake index file. Returns a human readable summary or throws
     * when the bytes are not a valid fake index.
     */
    public static String describeFakeIndex(byte[] b) {
        if (b.length != 24) {
            throw new IllegalArgumentException("fake index must be 24 bytes, got " + b.length);
        }
        long magic = u64(b, 0);
        if (magic != INITIAL_MAGIC) {
            throw new IllegalArgumentException(
                    "bad fake index magic 0x" + Long.toHexString(magic));
        }
        int version = u32(b, 8);
        int zero = u32(b, 12);
        int zero2 = u32(b, 16);
        int encryption = u32(b, 20);
        if (zero != 0 || zero2 != 0) {
            throw new IllegalArgumentException("fake index zero fields must be 0");
        }
        return "version=" + version + " encryption_status=" + encryption;
    }

    /**
     * base::Time internal value = microseconds since Windows epoch (1601-01-01).
     */
    public static long windowsEpochMicrosNow() {
        long unixMicros = System.currentTimeMillis() * 1000L;
        long delta = 11644473600L * 1000000L; // seconds between 1601 and 1970
        return unixMicros + delta;
    }

    public static long fromUnixMillis(long millis) {
        return millis * 1000L + 11644473600L * 1000000L;
    }

    public static long toUnixMillis(long windowsMicros) {
        return (windowsMicros - 11644473600L * 1000000L) / 1000L;
    }

    /** base::Pickle-style 4 byte alignment writer (used for the index file). */
    public static final class PickleWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void align4() {
            while ((out.size() & 3) != 0) {
                out.write(0);
            }
        }

        public PickleWriter writeU32(int v) {
            align4();
            byte[] b = new byte[4];
            putU32(b, 0, v);
            out.write(b, 0, 4);
            return this;
        }

        public PickleWriter writeI32(int v) {
            return writeU32(v);
        }

        public PickleWriter writeU64(long v) {
            align4();
            byte[] b = new byte[8];
            putU64(b, 0, v);
            out.write(b, 0, 8);
            return this;
        }

        public PickleWriter writeI64(long v) {
            return writeU64(v);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
