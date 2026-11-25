# Lazy-Proto

**Lazy-Proto** 是一个性能优先（Performance-First）的 Protocol Buffers 操纵引擎与代码生成设计，旨在通过 **按需延迟反序列化（Lazy Deserialization）** 与 **最大化复用原始输入字节的拼接式序列化（Input-Reusing Splicing Serialization）**，消除微服务网关、RPC 代理、流式处理管道中 90% 以上不必要的编解码与 GC 开销。

---

## 核心特性

1. **Architecture v3（Java 21）+ C++20 零开销引擎**
   - **Immutable Base + 外挂 COW Overlay + `WireMergeEngine`**：原始输入保持绝对不可变；`build()` 快照零深拷贝、多线程并发只读懒加载安全；`Builder.reuse()` 支持线程局部 `0 B/op` 复用。
   - **64-bit SWAR Varint 跳过 + `FieldTape`**：利用 WireType 2 (`LEN`) 长度前缀以 $O(1)$ 跨过所有未访问子消息、字符串与 `repeated` 列表。
   - **四级输入复用序列化**：Fast-Path 0 未改直通、Fast-Path 1 跨层级同长原地打补丁、Fast-Path 2 连续未改区间合并拼接、Fast-Path 3（C++20）单遍投机长度回填。
2. **双语言实现与无 Schema 动态文档**
   - **Java 21 接口兼容版**（[`src/main/java/io/lazyproto`](src/main/java/io/lazyproto)）：兼容 `Message` / `Message.Builder` / `XxxOrBuilder` 契约，并提供动态无 Schema 文档 [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)。
   - **C++20 极限标杆版**（[`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)）：栈内联 `TapeEntry` + 值内联子结构体 + `std::string_view` + 单遍投机长度回填，全场景 **0 B/op** 堆内存分配。

---

## 实测性能概览（同输出 Buffer 复用口径，AMD Ryzen 7 7840HS）

基于统一 [proto/order_envelope.proto](proto/order_envelope.proto) 生成真实官方类对比（Java：`protoc 3.25.5` + `protobuf-java 3.25.5` @ JDK 21；C++：`libprotobuf 3.21.12` + `GCC 15.2.0 -O3 -march=native -std=c++20`）：

| 场景类别 | 场景描述 | Java 官方 PB ReuseB | **Java `lazy-proto` v3 ReuseB** | C++ 官方 `libprotobuf` Reuse | **C++20 `lazy-proto` Reuse** |
| :--- | :--- | ---: | ---: | ---: | ---: |
| **优势 A1** | 只读 3 字段 (12.4 KB，无序列化) | 8,170 ns | **887 ns (9.21x, 0 B)** | 5,138 ns | **224 ns (22.98x, 0 B)** |
| **优势 A2** | 读 2 字段后原样转发 (Fast-Path 0) | 18,313 ns | **694 ns (26.40x, 0 B)** | 8,370 ns | **298 ns (28.05x, 0 B)** |
| **优势 A3** | 改 3 个定长/同长字段 (Fast-Path 1) | 18,320 ns | **1,018 ns (17.99x, 0 B)** | 8,490 ns | **318 ns (26.70x, 0 B)** |
| **优势 A4** | 修改变长字符串与嵌套 City (Fast-Path 2) | 18,350 ns | **1,070 ns (17.15x, 80 B)** | 8,452 ns | **371 ns (22.81x, 0 B)** |
| **优势 A5** | 100 项列表改第 42 项 + 序列化 | 19,392 ns | **2,025 ns (9.58x, 208 B)** | 8,473 ns | **358 ns (23.67x, 0 B)** |
| **优势 A6** | 网关脱敏删除 1 个字段 + 转发 | 18,474 ns | **1,214 ns (15.22x, 0 B)** | 9,800 ns | **343 ns (28.58x, 0 B)** |
| **劣势 B1** | 从零构建新消息 (10 子项) | 1,323 ns | **1,351 ns (0.98x)** | 710 ns | **431 ns (1.65x, 0 B)** |
| **劣势 B2** | 100% 全树读取 (100 子项) | 6,860 ns | **12,268 ns (0.56x)** | 5,233 ns | **1,101 ns (4.75x, 0 B)** |
| **劣势 B3** | 100% 全树变长修改 (20 子项) | 3,989 ns | **9,854 ns (0.40x)** | 2,411 ns | **969 ns (2.49x, 0 B)** |
| **边界 B4** | 11 字节极小包全读 + 全变长修改 | 221 ns | **216 ns (1.02x, 0 B)** | 40.6 ns | **24.7 ns (1.64x, 0 B)** |

> 完整含 Track I（每次分配 `toByteArray()` / `SerializeToString()`）、手写 Eager 基线及 JDK/GCC/Protobuf 版本说明的数据见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) 与 [DESIGN.md](DESIGN.md)。

---

## 运行指南

### Java 21 正确性与基准测试（自动使用 `protoc 3.25.5` 生成官方基线）
```bash
mvn test
```

### C++20 正确性与基准测试（需 `protobuf-compiler` 与 `libprotobuf-dev`）
```bash
make -C cpp run
```
