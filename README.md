# SimpleCache-Tool

解析、还原、修改 Chromium **Simple Cache** 的 Java 类库。

支持两类缓存：

- Chromium / Chrome 的 HTTP 磁盘缓存（`Default/HTTP Cache`）
- Android WebView 的 V8 code cache（`Code Cache`，键格式 `_key<url> \n`）

库本身不接触文件系统：所有方法都是 `byte[]` 进、`byte[]` 出，读文件与写文件由调用方自行实现。

## 特性

- 读取以索引文件（`index-dir/the-real-index`）为准，索引是存活条目的权威列表
- 还原：从索引出发，取出每个条目的 key 与响应体（stream 1）
- 修改：替换响应体并保持条目结构有效，自动重算 CRC32、key_hash，并同步 HTTP 元数据里的 `content-length`
- 解析：完整解析条目头、stream 0/1、EOF、SHA256(key)、索引 pickle
- 附带 WebView V8 code cache 的键与布局编解码（`CodeCache`）
- 纯 Java，无第三方依赖

## 环境要求

- JDK 21（`javac` / `jar`）

## 目录结构

```
simplecache-tool/
├── build.sh                     # 编译并打包为 simplecache.jar
├── simplecache.jar              # 构建产物（未纳入版本控制）
├── src/com/cfks/simplecache/
│   ├── SimpleCacheLibrary.java  # 对外 API：parse / restore / modify
│   ├── SimpleCacheFormat.java   # 常量、小端读写、哈希、fake index、文件名
│   ├── SimpleCacheEntry.java    # 条目文件 parse / serialize（stream 0/1）
│   ├── SimpleCacheIndex.java    # the-real-index 的 parse / serialize
│   ├── HttpCacheMetadata.java   # stream 0 中 content-length 的定位与修正
│   └── CodeCache.java           # WebView V8 code cache 的键与布局
├── examples/
│   └── LibraryTest.java         # 示例 / 冒烟测试（自己实现文件 I/O）
├── reference/chromium/          # 上游 Chromium 参考源码（BSD-3-Clause）
├── README.md
└── LICENSE
```

## 构建

```bash
cd simplecache-tool
./build.sh
```

产物为 `simplecache.jar`（无 `Main-Class`，作为类库使用）。

## API 使用说明

所有 API 位于 `com.cfks.simplecache.SimpleCacheLibrary`，签名均为 `byte[]` 入、`byte[]` 出。

### 1. 解析

```java
import com.cfks.simplecache.SimpleCacheIndex;
import com.cfks.simplecache.SimpleCacheEntry;
import com.cfks.simplecache.SimpleCacheLibrary;

byte[] indexBytes = readAllBytes(indexPath);          // index-dir/the-real-index
SimpleCacheIndex index = SimpleCacheLibrary.parseIndex(indexBytes);

byte[] entryBytes = readAllBytes(entryPath);          // <hash>_0
SimpleCacheEntry entry = SimpleCacheLibrary.parseEntry(entryBytes);
// entry.key / entry.stream0 / entry.stream1 / entry.warnings ...
```

辅助方法：

- `SimpleCacheLibrary.entryFileName(long hash)`：由索引里的 hash 得到文件名 `<hash>_0`
- `SimpleCacheLibrary.entryHash(byte[] key)`：由 key（如 URL）计算条目 hash
- `SimpleCacheLibrary.buildFakeIndex()`：生成 24 字节的 `index`（fake index）
- `SimpleCacheLibrary.describeFakeIndex(byte[] bytes)`：校验并描述 fake index

### 2. 还原

还原以索引为准：遍历索引条目，调用方按 hash 读取对应的 `<hash>_0`，库负责解析。

```java
import java.nio.file.*;
import java.util.List;
import com.cfks.simplecache.SimpleCacheLibrary;

Path cacheDir = Paths.get("/path/to/HTTP Cache");
byte[] indexBytes = Files.readAllBytes(cacheDir.resolve("index-dir/the-real-index"));

List<SimpleCacheLibrary.RestoredEntry> entries = SimpleCacheLibrary.restore(
        indexBytes,
        hash -> {
            Path p = cacheDir.resolve(SimpleCacheLibrary.entryFileName(hash));
            try {
                return Files.exists(p) ? Files.readAllBytes(p) : null;
            } catch (Exception e) {
                return null;
            }
        });

for (SimpleCacheLibrary.RestoredEntry e : entries) {
    String key = e.keyString();   // 例如 https://example.com/app.js
    byte[] body = e.body;         // stream 1，即响应体
    byte[] meta = e.metadata;     // stream 0，即响应元数据
    if (e.hasProblems()) {
        System.out.println(key + " -> " + e.warnings);
    }
    // 由调用方决定如何落盘
}
```

`RestoredEntry` 字段：

| 字段 | 说明 |
| --- | --- |
| `hash` | 索引中的 64 位条目 hash |
| `key` | 条目 key（`byte[]`），HTTP 缓存里是 URL |
| `body` | stream 1，响应体 |
| `metadata` | stream 0，响应元数据（HTTP 头等） |
| `entry` | 完整的 `SimpleCacheEntry` 对象，可能为 `null`（文件缺失时） |
| `warnings` | 解析与校验过程中发现的问题 |

### 3. 修改

替换条目响应体，返回新的条目字节与新的索引字节，由调用方写回。

```java
import com.cfks.simplecache.SimpleCacheFormat;
import com.cfks.simplecache.SimpleCacheLibrary;

byte[] entryBytes = Files.readAllBytes(entryPath);
byte[] newBody = /* 新的响应体，例如修改后的 JS */;

long nowInternal = SimpleCacheFormat.fromUnixMillis(System.currentTimeMillis());

SimpleCacheLibrary.ModifyResult result =
        SimpleCacheLibrary.modify(indexBytes, entryBytes, newBody, nowInternal);

Files.write(entryPath, result.entryFile);                                   // 新的 <hash>_0
Files.write(indexPath, result.indexFile);                                   // 新的 the-real-index
Files.write(cacheDir.resolve("index"), SimpleCacheLibrary.buildFakeIndex()); // fake index
```

也可以分开调用：

- `modifyEntryBody(byte[] entryFileBytes, byte[] newBody)` -> 新的条目字节
- `updateIndex(byte[] indexFileBytes, byte[] newEntryFileBytes, long lastUsedInternal)` -> 新的索引字节

修改时会：

- 保留 key、文件头与 stream 0
- 重算 stream 0/1 的 CRC32、文件头的 key_hash
- 同步 HTTP 元数据（stream 0）里的 `content-length`，并调整其 pickle 长度字段
- 刷新索引中的条目大小与 last-used 时间

注意：若条目带 `content-encoding: gzip`（缓存里存的是压缩字节），替换为明文时需要一并处理该响应头，目前库只同步 `content-length`。

## 示例程序

`examples/LibraryTest.java` 演示了完整的“读索引 -> 还原 -> 修改 -> 再还原”流程，并自行完成所有文件 I/O。

编译与运行：

```bash
cd simplecache-tool
javac -cp simplecache.jar -d . examples/LibraryTest.java
java  -cp simplecache.jar:. LibraryTest <cacheDir> <restoreOutDir> [modifyOutDir]
```

参数说明：

- `cacheDir`：Simple Cache 目录（HTTP 缓存或 WebView code cache）
- `restoreOutDir`：还原出的 `*.js` 输出目录
- `modifyOutDir`：可选，给出后会把第一个 `.js` 条目修改后写到该目录

## 格式说明（简要）

条目文件 `<hash>_0` 布局：

```
[SimpleFileHeader]        24 字节：u64 magic、u32 version、u32 key_length、u32 key_hash、u32 padding
[key]                     key_length 字节
[stream 1 data]           响应体
[SimpleFileEOF stream 1]  24 字节：u64 magic、u32 flags、u32 crc32、u32 stream_size、u32 padding
[stream 0 data]           响应元数据
[SHA256(key)]             32 字节，仅当 stream 0 EOF 带 HAS_KEY_SHA256
[SimpleFileEOF stream 0]  24 字节（文件末尾）
```

关键常量：

- 魔数：`INITIAL=0xfcfb6d1ba7725c30`、`FINAL=0xf4fa6f45970d41d8`、`INDEX=0x656e74657220796f`
- 版本：条目 `5`、索引 `9`、fake index（`kSimpleVersion`）`9`
- 文件名 hash：`SHA1(key)` 前 8 字节按小端解释为 u64，格式化为 `%016x`
- 文件头 `key_hash`：`base::PersistentHash`（Paul Hsieh SuperFastHash）
- 校验：`CRC32`（java.util.zip）、`SHA256`（MessageDigest）
- 索引：`base::Pickle`，8 字节头（u32 payload_size + u32 crc）后接 4 字节对齐字段
- `index`（fake index）：`u64 initial_magic、u32 version、u32 zero、u32 zero2、u32 encryption_status`，共 24 字节

WebView code cache 的键为 `_key<url> \n`（可带上下文 key），stream 0 头部为 `[response_time i64][data_size u32]`，随后按大小分为 INLINE / DEDICATED / INDIRECT 三种布局，详见 `CodeCache`。

## 参考

`reference/chromium/` 下为上游 Chromium 参考源码，来源为 Chromium 项目，遵循 BSD-3-Clause，版权归 The Chromium Authors 所有。主要参考文件：

- `net/disk_cache/simple/simple_entry_format.h`
- `net/disk_cache/simple/simple_index.cc`
- `net/disk_cache/simple/simple_index_file.cc`
- `net/disk_cache/simple/simple_synchronous_entry.cc`
- `net/disk_cache/simple/simple_util.cc`
- `net/disk_cache/simple/simple_version_upgrade.cc`
- `net/disk_cache/simple/simple_backend_impl.cc`
- `content/browser/code_cache/generated_code_cache.cc`

## License

MIT License，详见 [LICENSE](LICENSE)。
