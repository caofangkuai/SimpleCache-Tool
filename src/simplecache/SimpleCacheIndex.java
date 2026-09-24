package simplecache;

import java.util.ArrayList;
import java.util.List;

/**
 * Reader/writer for {@code index-dir/the-real-index}, a base::Pickle of
 * IndexMetadata followed by EntryMetadata records.
 *
 * See simple_index_file.cc / simple_index.cc.
 */
public final class SimpleCacheIndex {

    public static final int REASON_SHUTDOWN = 0;
    public static final int REASON_STARTUP_MERGE = 1;
    public static final int REASON_IDLE = 2;
    public static final int REASON_ANDROID_STOPPED = 3;

    public static final class Entry {
        public long hash;                // 64-bit entry hash (filename stem)
        public long lastUsedInternal;    // base::Time internal value (micros since 1601)
        public int entrySizeChunks;      // 30-bit, in 256 byte blocks
        public int inMemoryData;         // 2-bit

        public Entry() {}

        public Entry(long hash, long lastUsedInternal, long entrySize) {
            this.hash = hash;
            this.lastUsedInternal = lastUsedInternal;
            setEntrySize(entrySize);
        }

        public long packedInfo() {
            return ((long) entrySizeChunks << 8) | (inMemoryData & 0x3L);
        }

        public long getEntrySize() {
            return ((long) entrySizeChunks) << 8;
        }

        public void setEntrySize(long bytes) {
            long chunks = (bytes + 255) >> 8;
            if ((chunks >>> 30) != 0) {
                throw new IllegalArgumentException("entry too large: " + bytes);
            }
            entrySizeChunks = (int) chunks;
        }
    }

    public long magic = SimpleCacheFormat.INDEX_MAGIC;
    public int version = SimpleCacheFormat.INDEX_FILE_VERSION;
    public int reason = REASON_IDLE;
    public long cacheSize;
    public long cacheLastModifiedInternal;
    public final List<Entry> entries = new ArrayList<>();

    public final List<String> warnings = new ArrayList<>();

    // ------------------------------------------------------------------
    // parse
    // ------------------------------------------------------------------

    public static SimpleCacheIndex parse(byte[] data, boolean verifyCrc) {
        if (data.length < SimpleCacheFormat.INDEX_HEADER_SIZE) {
            throw new IllegalArgumentException("index file too small");
        }
        int payloadSize = SimpleCacheFormat.u32(data, 0);
        int crc = SimpleCacheFormat.u32(data, 4);
        if (SimpleCacheFormat.INDEX_HEADER_SIZE + payloadSize != data.length) {
            throw new IllegalArgumentException("index payload size mismatch: header=" + payloadSize
                    + " file=" + data.length);
        }
        SimpleCacheIndex idx = new SimpleCacheIndex();
        if (verifyCrc) {
            int actual = SimpleCacheFormat.crc32(data, SimpleCacheFormat.INDEX_HEADER_SIZE, payloadSize);
            if (actual != crc) {
                idx.warnings.add(String.format("pickle crc mismatch stored=0x%08x actual=0x%08x",
                        crc, actual));
            }
        }
        int off = SimpleCacheFormat.INDEX_HEADER_SIZE;
        idx.magic = SimpleCacheFormat.u64(data, off);
        off += 8;
        if (idx.magic != SimpleCacheFormat.INDEX_MAGIC) {
            throw new IllegalArgumentException(String.format("bad index magic 0x%016x", idx.magic));
        }
        idx.version = SimpleCacheFormat.u32(data, off);
        off += 4;
        if (idx.version < SimpleCacheFormat.MIN_INDEX_FILE_VERSION
                || idx.version > SimpleCacheFormat.INDEX_FILE_VERSION) {
            throw new IllegalArgumentException("unsupported index version " + idx.version);
        }
        long entryCount = SimpleCacheFormat.u64(data, off);
        off += 8;
        idx.cacheSize = SimpleCacheFormat.u64(data, off);
        off += 8;
        idx.reason = SimpleCacheFormat.u32(data, off);
        off += 4;

        for (long i = 0; i < entryCount; i++) {
            Entry e = new Entry();
            e.hash = SimpleCacheFormat.u64(data, off);
            off += 8;
            e.lastUsedInternal = SimpleCacheFormat.i64(data, off);
            off += 8;
            long packed = SimpleCacheFormat.u64(data, off);
            off += 8;
            if ((packed >>> 38) != 0) {
                throw new IllegalArgumentException("bad entry metadata");
            }
            e.entrySizeChunks = (int) (packed >>> 8);
            e.inMemoryData = (int) (packed & 0x3);
            idx.entries.add(e);
        }
        idx.cacheLastModifiedInternal = SimpleCacheFormat.i64(data, off);
        return idx;
    }

    // ------------------------------------------------------------------
    // serialize
    // ------------------------------------------------------------------

    public byte[] serialize() {
        SimpleCacheFormat.PickleWriter w = new SimpleCacheFormat.PickleWriter();
        w.writeU64(SimpleCacheFormat.INDEX_MAGIC);
        w.writeU32(SimpleCacheFormat.INDEX_FILE_VERSION);
        w.writeU64(entries.size());
        w.writeU64(cacheSize);
        w.writeU32(reason);
        for (Entry e : entries) {
            w.writeU64(e.hash);
            w.writeI64(e.lastUsedInternal);
            w.writeU64(e.packedInfo());
        }
        w.writeI64(cacheLastModifiedInternal);

        byte[] payload = w.toByteArray();
        byte[] out = new byte[SimpleCacheFormat.INDEX_HEADER_SIZE + payload.length];
        SimpleCacheFormat.putU32(out, 0, payload.length);
        SimpleCacheFormat.putU32(out, 4, SimpleCacheFormat.crc32(payload));
        System.arraycopy(payload, 0, out, SimpleCacheFormat.INDEX_HEADER_SIZE, payload.length);
        return out;
    }

    public Entry find(long hash) {
        for (Entry e : entries) {
            if (e.hash == hash) {
                return e;
            }
        }
        return null;
    }

    public void put(Entry e) {
        Entry existing = find(e.hash);
        if (existing != null) {
            existing.lastUsedInternal = e.lastUsedInternal;
            existing.entrySizeChunks = e.entrySizeChunks;
            existing.inMemoryData = e.inMemoryData;
        } else {
            entries.add(e);
        }
    }

    public void recomputeCacheSize() {
        long total = 0;
        for (Entry e : entries) {
            total += e.getEntrySize();
        }
        cacheSize = total;
    }
}
