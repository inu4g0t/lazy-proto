# Lazy-Proto

**Lazy-Proto** 是一个性能优先（Performance-First）的 Protocol Buffers 操纵引擎，采用 **“极简高性能 Lower Level（Input-Bytes Wrapper）+ Java 接口兼容中间层”** 分层设计，通过 **按需延迟反序列化 + 读写缓存 + 输入字节拼接序列化** 消除网关路由、RPC 代理、流式管道中不必要的编解码与堆分配开销。

---

## 核心架构分层

1. **Lower Level（极简核心，编译期强制零官方 Protobuf 依赖）**
   - **C++20 模块化头文件**（[`cpp/include/lazyproto/`](cpp/include/lazyproto/) + 伞状入口 [`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)）：拆分为 `wire.hpp`、`tape.hpp`、`wire_view.hpp`（基于显式 `FieldState` 状态机）、`order_envelope_view.hpp`，**不追求任何 `google::protobuf::Message` 接口兼容**，支持溢出安全的内联 `TapeStorage`、自动脏状态冒泡、单遍投机长度回填与全场景 **0 B/op** 复用。
   - **Java 21 `lazy-proto-core`**（[`lazy-proto-core/`](lazy-proto-core/)）：独立 Maven 模块，**零 `com.google.protobuf` 依赖**，包含 `WireTape`、`ScratchUtf8Arena`、`RawWireView`（`FieldState` 状态机）、`RawOrderEnvelope`。
2. **Java Intermediate Compatibility Layer（`lazy-proto-compat`）**
   - [`lazy-proto-compat/`](lazy-proto-compat/)：基于 `lazy-proto-core` 的统一 `WireTape` 扫描器，提供 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `WireMergeEngine` + `ProtobufCompat`，提供 `Message` / `Message.Builder` / `XxxOrBuilder` 常用读写、构筑、`validateDeep()` 校验与不可变并发快照，并提供无 Schema 动态文档 [`ProtoDocument`](lazy-proto-compat/src/main/java/io/lazyproto/dynamic/ProtoDocument.java)。
3. **验证与评测模块（`lazy-proto-benchmarks` & `cpp/tests`, `cpp/bench`）**
   - [`lazy-proto-benchmarks/`](lazy-proto-benchmarks/)：承载手写 Eager 基线 `StandardOrderProto`、`protoc 3.25.5` 官方对拍测试、状态机 Fuzzers 与 `@Tag("benchmark")` 基准套件。

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

> 说明：Java Lower Level 在全部 9 个非 `String` 解码场景中均为 `0 B/op`（B2 仅分配必选的 `15,520 B` `String`），C++20 Lower Reuse 在全部 10 个场景中均为 `0 B/op`。完整含 Java Track I、C++ `PB-Fresh`/`Lazy-Fresh`/`Lazy-WrapAlloc` 的详细数据见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) 与 [DESIGN.md](DESIGN.md)。

### Java 21 JMH 1.37 (`-prof gc`) 横向对比（Official PB vs QuickBuffers 1.4 vs LightProto 0.9.3 vs `lazy-proto`）

在对称复用对象 + 复用输出缓冲口径下（[JavaProtoJmhBenchmark.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/JavaProtoJmhBenchmark.java)，5 库语义由 [CrossLibraryParityTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/CrossLibraryParityTest.java) 验证）：

| 场景 | 工作负载描述 | Official PB 3.25.5 | QuickBuffers 1.4 | LightProto 0.9.3 | **`lazy-proto` Compat** | **`lazy-proto` Raw** | `Raw` vs OffPB / QB / LP |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | :---: |
| **A1** | 只读 3 字段 (12.4 KB) | 5,714 ns (34 KB) | 3,094 ns (0 B) | 2,459 ns (0 B) | **591 ns (0 B)** | **580 ns (0 B)** | **9.85x / 5.33x / 4.24x** |
| **A2** | 读 2 字段 + 原样转发 (FP0) | 18,176 ns (38 KB) | 4,873 ns (0 B) | 3,864 ns (0 B) | **645 ns (0 B)** | **627 ns (0 B)** | **28.97x / 7.77x / 6.16x** |
| **A3** | 改 3 同长/定长字段 (FP1) | 18,568 ns (38 KB) | 4,924 ns (0 B) | 4,047 ns (0 B) | **709 ns (0 B)** | **671 ns (0 B)** | **27.68x / 7.34x / 6.03x** |
| **A4** | 改变长字符串 + City (FP2) | 20,576 ns (38 KB) | 5,019 ns (0 B) | 4,115 ns (0 B) | **1,067 ns (80 B)** | **976 ns (0 B)** | **21.08x / 5.14x / 4.22x** |
| **A5** | 100 项列表改第 42 项 | 19,338 ns (39 KB) | 4,965 ns (0 B) | 4,058 ns (0 B) | **1,232 ns (200 B)** | **883 ns (0 B)** | **21.91x / 5.62x / 4.60x** |
| **A6** | 脱敏删 `auth_token` + 转发 | 18,799 ns (38 KB) | 4,850 ns (0 B) | 3,843 ns (0 B) | **872 ns (0 B)** | **741 ns (0 B)** | **25.36x / 6.54x / 5.18x** |
| **B1** | 从零构建新消息 (10 子项) | 1,012 ns (3.0 KB) | 489 ns (0 B) | **290 ns (0 B)** | 1,032 ns (1.1 KB) | 624 ns (0 B) | 1.62x / 0.78x / 0.46x |
| **B2** | 100% 全树读取 (100 子项) | 6,501 ns (34 KB) | 5,308 ns (15.5 KB) | **4,608 ns (15.5 KB)** | 10,421 ns (18.7 KB) | 8,629 ns (15.5 KB) | 0.75x / 0.62x / 0.53x |
| **B3** | 100% 全树变长改 (20 子项) | 3,658 ns (12.5 KB) | 1,609 ns (0 B) | **1,128 ns (0 B)** | 8,289 ns (4.7 KB) | 2,891 ns (0 B) | 1.27x / 0.56x / 0.39x |
| **B4** | 8 字节极小包全读 + 全改 | 45.2 ns (152 B) | **18.1 ns (0 B)** | 21.3 ns (0 B) | 62.0 ns (0 B) | 52.0 ns (0 B) | 0.87x / 0.35x / 0.41x |

> **选型边界一目了然**：稀疏读写/网关转发/局部修补（A1–A6）选 **`lazy-proto`**（比 LightProto 快 `4.2x–6.3x`，比 QuickBuffers 快 `5.1x–7.8x`，零 Netty 依赖且保留未知字段）；100% 全字段构筑/全树读改/极小包（B1–B4）选 **LightProto / QuickBuffers**（扁平强类型字段无 Tape 间接层，快 `1.3x–2.9x`）。

### 开放生态标准基准与交叉点扫描（`jvm-serializers` / Apache Pulsar / OpenTelemetry / Selectivity Sweep）

在 [OpenBenchmarksJmh.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/OpenBenchmarksJmh.java) 中对比社区标准 Schema（O1–O3 使用无代码生成通用 `RawWireView`；O4 使用 `RawOrderEnvelope`）：

| 开放场景 | 负载描述 | Official PB 3.25.5 | QuickBuffers 1.4 | LightProto 0.9.3 | **`lazy-proto` Raw** | `Lazy` vs OffPB / QB / LP |
| :--- | :--- | ---: | ---: | ---: | ---: | :---: |
| **O1c** | `jvm-serializers` `MediaContent` 改 `title` (241 B) | 642 ns (1.5 KB) | 256 ns (112 B) | **171 ns (0 B)** | 202 ns (0 B) | 3.17x / 1.27x / 0.85x |
| **O2b** | Apache Pulsar `MessageMetadata` 盖章转发 (489 B) | 1,295 ns (3.1 KB) | 351 ns (0 B) | 342 ns (0 B) | **217 ns (0 B)** | **5.96x / 1.62x / 1.57x** |
| **O3a** | **OpenTelemetry** Trace 路由转发 (13.4 KB, 50 Spans) | 36,675 ns (102 KB) | 12,210 ns (64 B) | 10,915 ns (64 B) | **321 ns (64 B)** | **114.39x / 38.08x / 34.04x** |
| **O3b** | **OpenTelemetry** 注入 Resource 属性 + 转发 | 37,063 ns (103 KB) | 12,735 ns (0 B) | 11,173 ns (0 B) | **489 ns (0 B)** | **75.86x / 26.06x / 22.87x** |
| **O3c** | **OpenTelemetry** 4 层深嵌套脱敏 1 条 PII 属性 | 37,852 ns (104 KB) | 12,795 ns (0 B) | 10,796 ns (0 B) | **985 ns (0 B)** | **38.42x / 12.99x / 10.96x** |
| **O3d** | OpenTelemetry 全量遍历 50 Spans × 6 属性 | 20,241 ns (90 KB) | 14,331 ns (38 KB) | **13,038 ns (38 KB)** | 29,372 ns (38 KB) | 0.69x / 0.49x / 0.44x |
| **O4 (1% / 25% / 50%)** | 100 项修改选择性扫描 (12.4 KB，均 `0 B/op`) | 19.1 / 18.5 / 19.5 µs | 5.0 / 4.9 / 5.8 µs | 3.9 / 4.1 / **3.9 µs** | **0.85 / 2.81** / 4.93 µs | **4.54x / 1.46x** / 0.79x (vs LP) |

> 完整 O1a–O4（含 `1% / 10% / 25% / 50% / 100%` 交叉点曲线：`~40%` 与 LightProto 交叉、`~65%` 与 QuickBuffers 交叉）见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md)。

### C++20 横向对比（`libprotobuf 3.21.12` Reuse / Arena vs Mapbox `protozero v1.8.2` vs `lazy-proto` C++20）

在 [open_benchmark_main.cpp](cpp/bench/open_benchmark_main.cpp) 中，全部 22 项场景 `lazy-proto` C++20 均实现严格 **`0 B/op`**（O1–O3 使用无代码生成通用 `WireView`）：

| 场景 | 负载描述 | `PB-Reuse` | `PB-Arena` | `protozero v1.8.2` | **`lazy-proto` C++20** | `Lazy` vs Reuse / Arena / PZ |
| :--- | :--- | ---: | ---: | ---: | ---: | :---: |
| **A3** | 改 3 同长/定长字段 (12.4 KB, FP1) | 8,463 ns | 11,353 ns | 1,126 ns (0 B) | **435 ns (0 B)** | **19.44x / 26.08x / 2.59x** |
| **A4** | 改变长字符串 + City (FP2) | 8,699 ns | 11,550 ns | 1,125 ns (0 B) | **555 ns (0 B)** | **15.68x / 20.82x / 2.03x** |
| **A6** | 脱敏删 `auth_token` + 转发 | 8,596 ns | 11,644 ns | 981 ns (0 B) | **475 ns (0 B)** | **18.09x / 24.50x / 2.06x** |
| **B2** | 100% 全树读取 (100 子项) | 5,448 ns | 8,505 ns | 4,522 ns (0 B) | **1,902 ns (0 B)** | **2.86x / 4.47x / 2.38x** |
| **O2b** | Pulsar `MessageMetadata` 盖章转发 (489 B) | 806 ns | 1,058 ns | 193 ns (0 B) | **165 ns (0 B)** | **4.88x / 6.40x / 1.17x** |
| **O3a** | **OpenTelemetry** Trace 路由转发 (13.4 KB) | 26,751 ns | 34,951 ns | **119 ns (0 B)** | 219 ns (0 B) | **121.91x / 159.28x** / 0.54x |
| **O3c** | **OpenTelemetry** 4 层深嵌套脱敏 PII | 26,853 ns | 35,282 ns | 773 ns (0 B) | **700 ns (0 B)** | **38.35x / 50.39x / 1.10x** |
| **O4 (1% / 25% / 50%)** | 100 项修改选择性扫描 (12.4 KB, 0 B) | 8.5 / 8.9 / 9.2 µs | 12.8 / 13.1 / 13.4 µs | 0.99 / 2.01 / **3.05 µs** | **0.58 / 1.88** / 3.20 µs | **1.73x / 1.07x** / 0.95x (vs PZ) |

> 完整 C++20 22 项横向评测数据（含 `MediaContent` O1、Pulsar O2、OpenTelemetry O3、Selectivity Sweep O4 完整曲线与 `protozero` 提前退出只读对比分析）以及各引擎**优劣势（Pros & Cons）与工程选型指南**见 [TECHNICAL_REPORT.md](TECHNICAL_REPORT.md) §4.1 & §5。

---

## 运行指南

### Java 21 正确性与状态机 Fuzz 测试（零依赖 Core + Compat + 官方 `protoc 3.25.5` 对拍）
```bash
mvn test
```

### Java 21 完整微基准套件与 JMH 横向对比
```bash
# Track I / Track II（Official PB vs Hand-Eager vs Lazy Compat / Raw）
mvn test -pl lazy-proto-benchmarks -Dgroups=benchmark -DexcludedGroups=

# JMH 1.37 (-prof gc) 5 库横向对比 & 开放标准基准（MediaContent / Pulsar / OpenTelemetry / Sweep）
mvn -q test-compile && mvn -q install -DskipTests
CP="lazy-proto-benchmarks/target/test-classes:lazy-proto-benchmarks/target/classes:$(mvn -q -pl lazy-proto-benchmarks dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" io.lazyproto.JavaProtoJmhBenchmark
java -cp "$CP" io.lazyproto.OpenBenchmarksJmh
```

### C++20 正确性、状态机 Fuzz（含 `-DNDEBUG` 与 ASan/UBSan）与基准测试
```bash
make -C cpp test
make -C cpp test-ndebug
make -C cpp test-asan
make -C cpp run
make -C cpp run-open   # PB-Reuse vs PB-Arena vs Mapbox protozero v1.8.2 vs lazy-proto（含 O1-O4 开放基准）
```
