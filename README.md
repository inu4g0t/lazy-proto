# Lazy-Proto

**Lazy-Proto** 是一个性能优先（Performance-First）的 Protocol Buffers 操纵引擎，采用 **“极简高性能 Lower Level（Input-Bytes Wrapper）+ Java 接口兼容中间层”** 分层设计，通过 **按需延迟反序列化 + 读写缓存 + 输入字节拼接序列化** 消除网关路由、RPC 代理、流式管道中不必要的编解码与堆分配开销。

---

## 核心架构分层

1. **Lower Level（极简核心，零官方 Protobuf 接口税）**
   - **C++20 纯底层版**（[`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)）：**不追求任何 `google::protobuf::Message` 接口兼容**，提供通用 [`WireView`](cpp/lazy_proto.hpp)（读取/缓存/修改任意字段号并合并原字节）与零成本强类型 `OrderEnvelopeView`，支持溢出安全的内联 `TapeStorage`、自动脏状态冒泡、单遍投机长度回填与全场景 **0 B/op** 复用。
   - **Java 21 Lower Level**（[`src/main/java/io/lazyproto/core`](src/main/java/io/lazyproto/core) + [`wire`](src/main/java/io/lazyproto/wire)）：零 `com.google.protobuf` 依赖的 `WireTape`、`ScratchUtf8Arena`、`RawOrderEnvelope`、`RawWireView`，单线程可变复用、单遍长度回填。
2. **Java Intermediate Compatibility Layer（接口兼容中间层）**
   - [`src/main/java/io/lazyproto/runtime`](src/main/java/io/lazyproto/runtime) + [`model`](src/main/java/io/lazyproto/model) + [`dynamic`](src/main/java/io/lazyproto/dynamic)：在底层之上提供 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `WireMergeEngine` + `ProtobufCompat`，提供 `Message` / `Message.Builder` / `XxxOrBuilder` 常用读写、构筑与序列化适配、`build()` 不可变快照与多线程并发懒加载（主动裁剪 `equals`、`hashCode`、反射 `Descriptor` 与 `UnknownFieldSet`，相关方法抛出 `UnsupportedOperationException`），并提供无 Schema 动态文档 [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)。

---

## 实测性能概览（同输出 Buffer 复用口径，AMD Ryzen 7 7840HS）

基于统一 [proto/order_envelope.proto](proto/order_envelope.proto) 生成真实官方类对比（Java：`protoc 3.25.5` + `protobuf-java 3.25.5` @ JDK 21；C++：`libprotobuf 3.21.12` + `GCC 15.2.0 -O3 -march=native -std=c++20`）：

| 场景类别 | 场景描述 | Java 官方 PB ReuseB | Java Compat ReuseB | **Java Lower `Raw`** | C++ 官方 `libprotobuf` Reuse | **C++20 Lower Reuse** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| **优势 A1** | 只读 3 字段 (12.4 KB，无序列化) | 8,428 ns | 683 ns (12.34x, 0 B) | **1,168 ns (7.22x, 0 B)** | 5,180 ns | **345 ns (15.02x, 0 B)** |
| **优势 A2** | 读 2 字段后原样转发 (Fast-Path 0) | 17,995 ns | 796 ns (22.62x, 0 B) | **627 ns (28.71x, 0 B)** | 8,552 ns | **403 ns (21.22x, 0 B)** |
| **优势 A3** | 改 3 个定长/同长字段 (Fast-Path 1) | 17,998 ns | 1,168 ns (15.42x, 0 B) | **1,083 ns (16.62x, 0 B)** | 8,569 ns | **438 ns (19.58x, 0 B)** |
| **优势 A4** | 修改变长字符串与嵌套 City (Fast-Path 2) | 18,120 ns | 1,119 ns (16.20x, 80 B) | **1,428 ns (12.69x, 0 B)** | 8,647 ns | **521 ns (16.61x, 0 B)** |
| **优势 A5** | 100 项列表改第 42 项 + 序列化 | 19,068 ns | 1,586 ns (12.02x, 208 B) | **1,578 ns (12.08x, 0 B)** | 8,501 ns | **527 ns (16.14x, 0 B)** |
| **优势 A6** | 网关脱敏删除 1 个字段 + 转发 | 18,327 ns | 1,056 ns (17.36x, 0 B) | **813 ns (22.55x, 0 B)** | 8,509 ns | **482 ns (17.66x, 0 B)** |
| **劣势 B1** | 从零构建新消息 (10 子项，独立 `clear()`) | 1,412 ns | 1,355 ns (1.04x) | **1,267 ns (1.11x, 0 B)** | 708 ns | **287 ns (2.47x, 0 B)** |
| **劣势 B2** | 100% 全树读取 (含全量 `String` 解码) | 7,017 ns | 18,437 ns (0.38x) | **15,160 ns (0.46x)** | 5,363 ns | **1,263 ns (4.25x, 0 B)** |
| **劣势 B3** | 100% 全树变长修改 (20 子项) | 4,224 ns | 9,788 ns (0.43x) | **4,345 ns (0.97x)** | 2,349 ns | **1,380 ns (1.70x, 0 B)** |
| **边界 B4** | 8 字节极小包全读 + 全变长修改 | 261 ns | 229 ns (1.14x, 0 B) | **195 ns (1.34x, 0 B)** | 43.7 ns | **38.1 ns (1.15x, 0 B)** |

> 说明：Java Lower Level 在 A1–A6、B1、B4 复用模式下均保持 `0 B/op`，而在 B2/B3 需按需全量解码并生成 JVM `String` 时会产生堆分配（B2 `15,520 B`、B3 `1,280 B`）并受限于全量懒解码开销；C++20 Lower Reuse 在全部 10 个场景中均为 `0 B/op`。完整含 Java Track I（每次分配 `toByteArray()`）、C++ `PB-Fresh` vs `Lazy-Fresh` vs `Lazy-WrapAlloc`、手写 Eager 基线的数据见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) 与 [DESIGN.md](DESIGN.md)。

---

## 运行指南

### Java 21 正确性与基准测试（自动匹配平台 `protoc 3.25.5` 生成官方基线）
```bash
mvn test
```

### C++20 正确性与基准测试（需 `protobuf-compiler` 与 `libprotobuf-dev`）
```bash
make -C cpp run
```
