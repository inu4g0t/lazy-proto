# Lazy-Proto

**Lazy-Proto** 是一个性能优先（Performance-First）的 Protocol Buffers 操纵引擎，采用 **“极简高性能 Lower Level（Input-Bytes Wrapper）+ Java 接口兼容中间层”** 分层设计，通过 **按需延迟反序列化 + 读写缓存 + 输入字节拼接序列化** 消除网关路由、RPC 代理、流式管道中不必要的编解码与堆分配开销。

---

## 核心架构分层

1. **Lower Level（极简核心，零官方 Protobuf 接口税）**
   - **C++20 纯底层版**（[`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)）：**不追求任何 `google::protobuf::Message` 接口兼容**，提供通用 [`WireView`](cpp/lazy_proto.hpp)（读取/缓存/修改任意字段号并合并原字节）与零成本强类型 `OrderEnvelopeView`，支持溢出安全的内联 `TapeStorage`、自动脏状态冒泡、单遍投机长度回填与全场景 **0 B/op** 复用。
   - **Java 21 Lower Level**（[`src/main/java/io/lazyproto/core`](src/main/java/io/lazyproto/core) + [`wire`](src/main/java/io/lazyproto/wire)）：零 `com.google.protobuf` 依赖的 `WireTape`、`ScratchUtf8Arena`、`RawOrderEnvelope`、`RawWireView`，单线程可变复用、单遍长度回填。
2. **Java Intermediate Compatibility Layer（接口兼容中间层）**
   - [`src/main/java/io/lazyproto/runtime`](src/main/java/io/lazyproto/runtime) + [`model`](src/main/java/io/lazyproto/model) + [`dynamic`](src/main/java/io/lazyproto/dynamic)：在底层之上提供 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `WireMergeEngine` + `ProtobufCompat`，完整适配 `Message` / `Message.Builder` / `XxxOrBuilder` 契约、`build()` 不可变快照与多线程并发懒加载，并提供无 Schema 动态文档 [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)。

---

## 实测性能概览（同输出 Buffer 复用口径，AMD Ryzen 7 7840HS）

基于统一 [proto/order_envelope.proto](proto/order_envelope.proto) 生成真实官方类对比（Java：`protoc 3.25.5` + `protobuf-java 3.25.5` @ JDK 21；C++：`libprotobuf 3.21.12` + `GCC 15.2.0 -O3 -march=native -std=c++20`）：

| 场景类别 | 场景描述 | Java 官方 PB ReuseB | Java Compat ReuseB | **Java Lower `Raw`** | C++ 官方 `libprotobuf` Reuse | **C++20 Lower Reuse** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| **优势 A1** | 只读 3 字段 (12.4 KB，无序列化) | 8,062 ns | 733 ns (11.00x, 0 B) | **568 ns (14.20x, 0 B)** | 5,034 ns | **354 ns (14.23x, 0 B)** |
| **优势 A2** | 读 2 字段后原样转发 (Fast-Path 0) | 18,174 ns | 680 ns (26.74x, 0 B) | **603 ns (30.14x, 0 B)** | 8,299 ns | **396 ns (20.94x, 0 B)** |
| **优势 A3** | 改 3 个定长/同长字段 (Fast-Path 1) | 18,657 ns | 838 ns (22.25x, 0 B) | **880 ns (21.21x, 0 B)** | 8,496 ns | **420 ns (20.23x, 0 B)** |
| **优势 A4** | 修改变长字符串与嵌套 City (Fast-Path 2) | 18,450 ns | 1,075 ns (17.16x, 80 B) | **2,309 ns (7.99x, 0 B)** | 8,592 ns | **518 ns (16.58x, 0 B)** |
| **优势 A5** | 100 项列表改第 42 项 + 序列化 | 19,094 ns | 1,518 ns (12.58x, 208 B) | **821 ns (23.26x, 0 B)** | 8,410 ns | **559 ns (15.05x, 0 B)** |
| **优势 A6** | 网关脱敏删除 1 个字段 + 转发 | 18,270 ns | 1,112 ns (16.43x, 0 B) | **887 ns (20.60x, 0 B)** | 8,381 ns | **487 ns (17.22x, 0 B)** |
| **劣势 B1** | 从零构建新消息 (10 子项，独立 `clear()`) | 2,353 ns | 1,470 ns (1.60x) | **1,309 ns (1.80x, 0 B)** | 692 ns | **298 ns (2.32x, 0 B)** |
| **劣势 B2** | 100% 全树读取 (100 子项) | 7,994 ns | 12,119 ns (0.66x) | **3,684 ns (2.17x)** | 5,204 ns | **1,191 ns (4.37x, 0 B)** |
| **劣势 B3** | 100% 全树变长修改 (20 子项) | 3,974 ns | 9,841 ns (0.40x) | **5,295 ns (0.75x)** | 2,286 ns | **1,430 ns (1.60x, 0 B)** |
| **边界 B4** | 8 字节极小包全读 + 全变长修改 | 185 ns | 196 ns (0.94x, 0 B) | **134 ns (1.38x, 0 B)** | 43.4 ns | **41.4 ns (1.05x, 0 B)** |

> 完整含 Java Track I（每次分配 `toByteArray()`）、C++ `PB-Fresh` vs `Lazy-Fresh` vs `Lazy-WrapAlloc`、手写 Eager 基线的数据见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) 与 [DESIGN.md](DESIGN.md)。

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
