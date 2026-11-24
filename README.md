# Lazy-Proto

**Lazy-Proto** 是一个性能优先（Performance-First）的 Protocol Buffers 操纵引擎与代码生成设计，旨在通过 **按需延迟反序列化（Lazy Deserialization）** 与 **最大化复用原始输入字节的拼接式序列化（Input-Reusing Splicing Serialization）**，消除微服务网关、RPC 代理、流式处理管道中 90% 以上不必要的编解码与 GC 开销。

---

## 核心特性

1. **三级延迟反序列化（参考 `json-iterator`）**
   - **Tier 0 (`wrap`)**：$O(1)$ 绑定原始字节切片，零扫描、零堆分配。
   - **Tier 1 (`ShallowScanner`)**：首次访问某层字段时，利用 64-bit SWAR (`TZCNT`) 无分支跳过 Varint，利用 WireType 2 长度前缀以 $O(1)$ 跨过所有未访问的子消息、字符串与列表，将字段边界 `[tagOffset, valueOffset, recordEnd)` 记录于紧凑的 16-Byte `FieldTape` 中。
   - **Tier 2（按需物化与位图缓存）**：仅解码被访问的字段；已读但未修改的字段（如长字符串、大子消息）在序列化时**直接拷贝原输入字节切片**，绝不二次编码。
2. **四级输入复用序列化引擎**
   - **Fast-Path 0（未修改直通）**：单次 `memcpy` / `System.arraycopy` 或零拷贝 `ByteString` 切片直出。
   - **Fast-Path 1（跨层级同长原地修补 In-Place Patching）**：修改 `fixed32/64`、`double/float` 或编码后字节长度未变的 `varint`/`string` 时，无需重算任何外层父消息长度，直接在目标偏移处 $O(1)$ 覆写字节。
   - **Fast-Path 2（连续原始区间合并拼接 Contiguous Span Coalescing）**：变长修改或删除字段时，自动将所有相邻未修改字段（连同原始 Tag、Length Varint 及未知字段 Unknown Fields）合并为连续区间整块复制，仅重编码脏字段。
   - **Fast-Path 3（C++20 单遍投机长度回填）**：单遍写出脏子消息并原地回填长度前缀，省去 `ByteSizeLong()` 第一遍尺寸遍历。
3. **双语言实现**
   - **Java 21 接口兼容版**（`src/`）：同时实现官方 `com.google.protobuf.Message`、`Message.Builder` 与 `XxxOrBuilder` 接口，支持 `toBuilder().setXxx().build()` 零分配合一视图；对破坏 Lazy 的方法（`equals`、`hashCode`、反射 `Descriptor`、`UnknownFieldSet`）直接抛出 `UnsupportedOperationException`。同时提供类 `jsoniter.Any` 的动态无 Schema API [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)。
   - **C++20 硬件极限标杆版**（`cpp/`）：无兼容性包袱，采用栈内联 `TapeEntry` + 值内联子结构体 + `std::string_view` + 单遍投机回填，全场景 **0 Bytes/op 堆内存分配**。

---

## 实测性能概览（12.4 KB 复杂嵌套消息 / 100 子项，AMD Ryzen 7 7840HS）

| 场景类别 | 场景描述 | Java 官方 PB | **Java `lazy-proto`** | C++ 标准 PB | **C++20 `lazy-proto`** |
| :--- | :--- | ---: | ---: | ---: | ---: |
| **优势集 A1** | 只读 3 个字段 (Partial Read) | 9,004 ns | **1,258 ns (7.16x)** | 5,124 ns | **221 ns (23.16x)** |
| **优势集 A2** | 读 2 字段后原样转发 (Fast-Path 0) | 20,837 ns | **718 ns (29.04x)** | 6,204 ns | **327 ns (18.99x)** |
| **优势集 A3** | 修改 3 个定长/同长字段 (Fast-Path 1) | 20,334 ns | **802 ns (25.37x)** | 6,237 ns | **314 ns (19.87x)** |
| **优势集 A4** | 修改变长字符串与嵌套字段 (Fast-Path 2) | 18,810 ns | **2,256 ns (8.34x)** | 6,243 ns | **365 ns (17.10x)** |
| **优势集 A5** | 100 项列表仅修改第 42 项 + 序列化 | 18,749 ns | **1,520 ns (12.33x)** | 6,267 ns | **363 ns (17.25x)** |
| **优势集 A6** | 网关脱敏删除 1 个敏感字段 + 转发 | 18,615 ns | **833 ns (22.35x)** | 6,222 ns | **321 ns (19.41x)** |
| **无优势集 B1** | 从零构建新消息 (0% 输入可复用) | 2,529 ns | **2,696 ns (0.94x)** | 256 ns | **453 ns (0.57x)** |
| **无优势集 B2** | 100% 全树读取 (全部 513 个字段) | 7,532 ns | **12,951 ns (0.58x)** | 5,166 ns | **957 ns (5.40x)** |
| **无优势集 B3** | 100% 全字段变长修改 + 重序列化 | 8,513 ns | **6,979 ns (1.22x)** | 1,612 ns | **959 ns (1.68x)** |
| **无优势集 B4** | 11 字节极小消息全读 + 全变长修改 | 376 ns | **602 ns (0.62x)** | 25 ns | **23 ns (1.11x)** |

---

## 文档与运行指南

- **详细架构设计文档**：[DESIGN.md](DESIGN.md)
- **完整技术与基准评测报告**：[TECHNICAL_REPORT.md](TECHNICAL_REPORT.md)

### 运行 Java 21 正确性测试与基准测试
```bash
mvn test
```

### 运行 C++20 标杆正确性测试与基准测试
```bash
cd cpp
g++ -O3 -march=native -std=c++20 -Wall -Wextra benchmark_main.cpp -o benchmark_cpp
./benchmark_cpp
```
