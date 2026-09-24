package simplecache;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory representation of a Simple Cache entry file (the {@code <hash>_0}
 * file holding stream 0 and stream 1).
 *
 * On-disk layout (see simple_entry_format.h / simple_synchronous_entry.cc):
 *
 * <pre>
 *   [SimpleFileHeader]                24 bytes
 *   [key]                             key_length bytes
 *   [stream 1 data]                   response body (JS / V8 cached data)
 *   [SimpleFileEOF for stream 1]      24 bytes
 *   [stream 0 data]                   response metadata
 *   [SHA256(key)]                     32 bytes, only if FLAG_HAS_KEY_SHA256
 *   [SimpleFileEOF for stream 0]      24 bytes
 * </pre>
 */
public final class SimpleCacheEntry {

    public static final class Eof {
        public long magic;
        public int flags;
        public int crc32;
        public int streamSize;
        public int padding;

        public boolean hasCrc32() {
            return (flags & SimpleCacheFormat.FLAG_HAS_CRC32) != 0;
        }

        public boolean hasKeySha256() {
            return (flags & SimpleCacheFormat.FLAG_HAS_KEY_SHA256) != 0;
        }

        public static Eof read(byte[] d, int off) {
            Eof e = new Eof();
            e.magic = SimpleCacheFormat.u64(d, off);
            e.flags = SimpleCacheFormat.u32(d, off + 8);
            e.crc32 = SimpleCacheFormat.u32(d, off + 12);
            e.streamSize = SimpleCacheFormat.u32(d, off + 16);
            e.padding = SimpleCacheFormat.u32(d, off + 20);
            return e;
        }

        public void write(byte[] d, int off) {
            SimpleCacheFormat.putU64(d, off, magic);
            SimpleCacheFormat.putU32(d, off + 8, flags);
            SimpleCacheFormat.putU32(d, off + 12, crc32);
            SimpleCacheFormat.putU32(d, off + 16, streamSize);
            SimpleCacheFormat.putU32(d, off + 20, padding);
        }
    }

    public long initialMagic = SimpleCacheFormat.INITIAL_MAGIC;
    public int version = SimpleCacheFormat.ENTRY_VERSION;
    public int keyLength;
    public int keyHash;
    public int headerPadding;

    public byte[] key;
    public byte[] stream1 = new byte[0];   // body
    public Eof eof1 = new Eof();
    public byte[] stream0 = new byte[0];   // metadata
    public byte[] keySha256;               // may be null
    public Eof eof0 = new Eof();

    /** Non fatal problems discovered while parsing. */
    public final List<String> warnings = new ArrayList<>();

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    public static SimpleCacheEntry parse(byte[] data, boolean verify) {
        if (data.length < SimpleCacheFormat.HEADER_SIZE + SimpleCacheFormat.EOF_SIZE) {
            throw new IllegalArgumentException("file too small: " + data.length);
        }
        SimpleCacheEntry e = new SimpleCacheEntry();
        e.initialMagic = SimpleCacheFormat.u64(data, 0);
        e.version = SimpleCacheFormat.u32(data, 8);
        e.keyLength = SimpleCacheFormat.u32(data, 12);
        e.keyHash = SimpleCacheFormat.u32(data, 16);
        e.headerPadding = SimpleCacheFormat.u32(data, 20);

        if (e.initialMagic != SimpleCacheFormat.INITIAL_MAGIC) {
            throw new IllegalArgumentException(
                    String.format("bad initial magic 0x%016x", e.initialMagic));
        }
        int keyEnd = SimpleCacheFormat.HEADER_SIZE + e.keyLength;
        if (keyEnd > data.length) {
            throw new IllegalArgumentException("key extends past end of file");
        }
        e.key = SimpleCacheFormat.slice(data, SimpleCacheFormat.HEADER_SIZE, e.keyLength);

        // stream 0 EOF is the very last record.
        int eof0Off = data.length - SimpleCacheFormat.EOF_SIZE;
        e.eof0 = Eof.read(data, eof0Off);
        if (e.eof0.magic != SimpleCacheFormat.FINAL_MAGIC) {
            throw new IllegalArgumentException(
                    String.format("bad stream0 EOF magic 0x%016x", e.eof0.magic));
        }

        int stream0Size = e.eof0.streamSize;
        int extra = e.eof0.hasKeySha256() ? SimpleCacheFormat.SHA256_SIZE : 0;
        int stream0Start = eof0Off - extra - stream0Size;
        int eof1Off = stream0Start - SimpleCacheFormat.EOF_SIZE;
        if (eof1Off < keyEnd) {
            throw new IllegalArgumentException("computed stream1 EOF before key end");
        }
        e.eof1 = Eof.read(data, eof1Off);
        if (e.eof1.magic != SimpleCacheFormat.FINAL_MAGIC) {
            throw new IllegalArgumentException(
                    String.format("bad stream1 EOF magic 0x%016x", e.eof1.magic));
        }
        if (stream0Size < 0 || stream0Start < eof1Off + SimpleCacheFormat.EOF_SIZE) {
            throw new IllegalArgumentException("invalid stream0 size");
        }

        e.stream1 = SimpleCacheFormat.slice(data, keyEnd, eof1Off - keyEnd);
        e.stream0 = SimpleCacheFormat.slice(data, stream0Start, stream0Size);
        if (extra == SimpleCacheFormat.SHA256_SIZE) {
            e.keySha256 = SimpleCacheFormat.slice(data, eof0Off - SimpleCacheFormat.SHA256_SIZE,
                    SimpleCacheFormat.SHA256_SIZE);
        }

        if (verify) {
            e.verify();
        }
        return e;
    }

    public void verify() {
        if (SimpleCacheFormat.keyHash(key) != keyHash) {
            warnings.add("header key_hash mismatch");
        }
        if (eof1.hasCrc32() && SimpleCacheFormat.crc32(stream1) != eof1.crc32) {
            warnings.add("stream1 crc32 mismatch");
        }
        if (eof0.hasCrc32() && SimpleCacheFormat.crc32(stream0) != eof0.crc32) {
            warnings.add("stream0 crc32 mismatch");
        }
        if (eof0.hasKeySha256() && keySha256 != null) {
            byte[] expect = SimpleCacheFormat.sha256(key);
            if (!java.util.Arrays.equals(expect, keySha256)) {
                warnings.add("sha256(key) mismatch");
            }
        }
    }

    // ------------------------------------------------------------------
    // serialization
    // ------------------------------------------------------------------

    public byte[] serialize(boolean withKeySha256) {
        if (key == null) {
            throw new IllegalStateException("key is required");
        }
        keyLength = key.length;
        keyHash = SimpleCacheFormat.keyHash(key);
        headerPadding = 0;

        eof1 = new Eof();
        eof1.magic = SimpleCacheFormat.FINAL_MAGIC;
        eof1.flags = SimpleCacheFormat.FLAG_HAS_CRC32;
        eof1.crc32 = SimpleCacheFormat.crc32(stream1);
        eof1.streamSize = 0;
        eof1.padding = 0;

        eof0 = new Eof();
        eof0.magic = SimpleCacheFormat.FINAL_MAGIC;
        eof0.flags = SimpleCacheFormat.FLAG_HAS_CRC32
                | (withKeySha256 ? SimpleCacheFormat.FLAG_HAS_KEY_SHA256 : 0);
        eof0.crc32 = SimpleCacheFormat.crc32(stream0);
        eof0.streamSize = stream0.length;
        eof0.padding = 0;

        byte[] header = new byte[SimpleCacheFormat.HEADER_SIZE];
        SimpleCacheFormat.putU64(header, 0, initialMagic);
        SimpleCacheFormat.putU32(header, 8, version);
        SimpleCacheFormat.putU32(header, 12, keyLength);
        SimpleCacheFormat.putU32(header, 16, keyHash);
        SimpleCacheFormat.putU32(header, 20, headerPadding);

        byte[] eof1Bytes = new byte[SimpleCacheFormat.EOF_SIZE];
        eof1.write(eof1Bytes, 0);
        byte[] eof0Bytes = new byte[SimpleCacheFormat.EOF_SIZE];
        eof0.write(eof0Bytes, 0);

        byte[] sha = withKeySha256 ? SimpleCacheFormat.sha256(key) : new byte[0];
        keySha256 = withKeySha256 ? sha : null;

        return SimpleCacheFormat.concat(header, key, stream1, eof1Bytes, stream0, sha, eof0Bytes);
    }

    /** Total on-disk size this entry would occupy once serialized. */
    public int serializedSize(boolean withKeySha256) {
        return SimpleCacheFormat.HEADER_SIZE + key.length + stream1.length
                + SimpleCacheFormat.EOF_SIZE + stream0.length
                + (withKeySha256 ? SimpleCacheFormat.SHA256_SIZE : 0)
                + SimpleCacheFormat.EOF_SIZE;
    }

    public String keyString() {
        return new String(key, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // stream 2 file (the <hash>_1 file)
    // ------------------------------------------------------------------

    public static final class Stream2 {
        public byte[] key;
        public byte[] data;
        public Eof eof;

        public static Stream2 parse(byte[] d) {
            Stream2 s = new Stream2();
            long magic = SimpleCacheFormat.u64(d, 0);
            if (magic != SimpleCacheFormat.INITIAL_MAGIC) {
                throw new IllegalArgumentException("bad stream2 initial magic");
            }
            int klen = SimpleCacheFormat.u32(d, 12);
            s.key = SimpleCacheFormat.slice(d, SimpleCacheFormat.HEADER_SIZE, klen);
            int dataStart = SimpleCacheFormat.HEADER_SIZE + klen;
            int eofOff = d.length - SimpleCacheFormat.EOF_SIZE;
            s.eof = Eof.read(d, eofOff);
            s.data = SimpleCacheFormat.slice(d, dataStart, eofOff - dataStart);
            return s;
        }

        public byte[] serialize() {
            Eof e = new Eof();
            e.magic = SimpleCacheFormat.FINAL_MAGIC;
            e.flags = SimpleCacheFormat.FLAG_HAS_CRC32;
            e.crc32 = SimpleCacheFormat.crc32(data);
            e.streamSize = 0;
            byte[] header = new byte[SimpleCacheFormat.HEADER_SIZE];
            SimpleCacheFormat.putU64(header, 0, SimpleCacheFormat.INITIAL_MAGIC);
            SimpleCacheFormat.putU32(header, 8, SimpleCacheFormat.ENTRY_VERSION);
            SimpleCacheFormat.putU32(header, 12, key.length);
            SimpleCacheFormat.putU32(header, 16, SimpleCacheFormat.keyHash(key));
            byte[] eofBytes = new byte[SimpleCacheFormat.EOF_SIZE];
            e.write(eofBytes, 0);
            return SimpleCacheFormat.concat(header, key, data, eofBytes);
        }
    }
}
