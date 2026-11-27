# Lazy-Proto 技术总结报告：分层架构（Lower Level + Java Compat）与真实官方基线对比

> **报告概述**：本报告基于统一 Schema [proto/order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)，对 `lazy-proto` 的两层设计——**Lower Level（Java `io.lazyproto.core` + C++20 无兼容 Input-Bytes Wrapper）**与 **Java Intermediate Compat Layer（`Message`/`Builder` 兼容）**——与真实官方 `protoc 3.25.5` (Java) / `libprotobuf 3.21.12` (C++) 及手写 Eager 基线进行了同生命周期、同输出缓冲口径（含对称防 DCE 保活）的交叉验证与微基准评测。

---

## 1. 分层架构与核心算法创新

1. **Lower Level（极简高性能 Input-Bytes Wrapper）**：
   - **Java `io.lazyproto.core`**（[`WireTape`](src/main/java/io/lazyproto/core/WireTape.java)、[`RawOrderEnvelope`](src/main/java/io/lazyproto/core/RawOrderEnvelope.java)、[`RawWireView`](src/main/java/io/lazyproto/core/RawWireView.java)）与 **C++20**（[`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)：`WireView` / `OrderEnvelopeView`）：
   - 零 `protobuf` 官方接口依赖，本质是对输入字节切片的轻量缓存包装器：单层浅扫描（SWAR Varint 跳过）+ 读写统一缓存 + 自动脏传播 + 4 级拼接序列化（FP0 直通、FP1 原地修补、FP2 连续区间合并、FP3 单遍投机长度回填）。
   - **C++ 定位**：**不追求任何 `google::protobuf::Message` 接口兼容**，直接交付纯 Lower Level。
2. **Java Intermediate Compatibility Layer（接口兼容中间层）**：
   - [`io.lazyproto.runtime`](src/main/java/io/lazyproto/runtime) + [`LazyOrderProto`](src/main/java/io/lazyproto/model/LazyOrderProto.java) + [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)：在底层之上叠加 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `ProtobufCompat`，提供 `Message` / `Builder` / `XxxOrBuilder` 常用读写、构筑与 `build()` 不可变快照适配（主动裁剪 `equals`、`hashCode`、反射 `Descriptor` 与 `UnknownFieldSet`）。

---

## 2. 实验环境、版本与严格对等对比口径

### 2.1 软硬件与工具链版本
- **CPU**：AMD Ryzen 7 7840HS (Zen 4)
- **操作系统**：Ubuntu Linux x86_64
- **Java 21 官方基线与横向对比库**：`Eclipse Temurin OpenJDK 21.0.13.1-LTS`，官方 `com.google.protobuf:protoc:3.25.5` + `protobuf-java 3.25.5`、**QuickBuffers `1.4`** (`us.hebi.quickbuf`, `allocation=lazy` + `clearQuick()`)、**LightProto `0.9.3`** (`io.streamnative.lightproto` + Netty `4.1.115.Final` heap `ByteBuf`)、**JMH `1.37`** (`-prof gc`)；手写 Eager 基线为 [StandardOrderProto.java](lazy-proto-benchmarks/src/main/java/io/lazyproto/model/StandardOrderProto.java)。
- **C++20 官方基线与横向对比库**：`GCC 15.2.0` (`-O3 -march=native -std=c++20 -Wall -Wextra -lprotobuf`)，`libprotoc 3.21.12` + `libprotobuf-dev 3.21.12`（含 **`PB-Reuse`** 与预分配 512 KB 块 **`google::protobuf::Arena`** `PB-Arena` 模式）、**Mapbox `protozero v1.8.2`**（零拷贝流式 `pbf_reader` + `basic_pbf_writer<fixed_size_buffer_adaptor>`）；手写 Eager 基线为 [std_proto.hpp](cpp/bench/std_proto.hpp)。

### 2.2 对比口径与对称校验原则
- **Java Track I（同不可变生命周期 + 每次分配 `toByteArray()`）**：官方 PB / 手写 Eager / Compat `LazyImm` 均走 `parseFrom -> toBuilder -> build -> toByteArray()`。
- **Java Track II（统一复用 `reusableOutBuf`）**：
  - `OffImm` vs `CompatImm`：不可变快照 + `outBuf`；
  - `OffReuseB` vs `CompatReuseB`：线程局部复用 `Builder`（`clear().mergeFrom()` / `reuse()`；`B1` 独立测量 `clear() -> getUserBuilder() + addItemsBuilder()`）；
  - **`RawLower` (`RawOrderEnvelope`)**：Java Lower Level 零兼容税基准（`wrap()` / `initScratch()` + 1-pass `writeToBuffer()`；`B2` 对齐全树 `String` 解码）。
- **C++ 三生命周期严格区分（均使用对称 `observe_output` 防 DCE/DSE，且用 `LP_CHECK` 确保 `-DNDEBUG` 下不跳过解析与输入生成）**：
  - **`PB-Fresh` vs `Lazy-Fresh`**：每轮构造全新消息对象 + 分配输出 `std::string`；
  - **`Lazy-WrapAlloc`**：复用外部 `OrderEnvelopeView`（`wrap()`/`init_scratch()`）+ 分配输出 `std::string`；
  - **`PB-Reuse` vs `Lazy-Reuse`**：复用消息对象 + 复用 `out_buf`。

主测试包：12.4 KB 复杂嵌套 `OrderEnvelope`（100 个 `LineItem` + 4 KB `payload` + 2 个未知字段 `#50, #51`）；B3 为 20 个 `LineItem`；B4 为 **8 字节** 3 整数极小包（`order_id=1001, status=1, total_cents=5000`）。

---

## 3. Java 21 实测基准数据（Track I & Track II）

### 3.1 Java Track I：同不可变生命周期 + `toByteArray()`

| 场景编号 | 工作负载描述 | 官方 `protoc` PB (`ns/op`) | 手写 Eager (`ns/op`) | **Compat `Imm` (`ns/op`)** | `vs 官方 PB` | 官方 PB 堆分配 | **Compat 堆分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 个字段 (12.4 KB，无序列化) | 9,432.6 | 5,843.7 | **2,437.7** | **3.87x** | 34,961 B | **3,644 B** |
| **A2** | 读 2 字段 + 原样转发 (FP0) | 19,375.6 | 21,356.5 | **1,475.1** | **13.14x** | 50,936 B | **15,760 B** |
| **A3** | 改 3 个定长/同长字段 (FP1) | 19,329.3 | 18,650.8 | **2,948.7** | **6.56x** | 51,312 B | **16,552 B** |
| **A4** | 改变长字符串与嵌套 City (FP2) | 19,013.2 | 18,419.6 | **3,057.2** | **6.22x** | 51,344 B | **16,667 B** |
| **A5** | 100 项列表改第 42 项 | 19,826.0 | 18,108.9 | **3,529.3** | **5.62x** | 52,640 B | **17,075 B** |
| **A6** | 脱敏删 1 个字段 + 转发 | 18,645.3 | 18,077.6 | **2,131.5** | **8.75x** | 51,088 B | **16,136 B** |
| **B1** | 从零构建新消息 (10 子项) | 2,130.8 | 2,539.8 | **3,353.6** | **0.64x** | 4,224 B | **4,024 B** |
| **B2** | **100% 全树读取** (100 子项，含 `String`) | 6,634.5 | 5,257.8 | **14,866.2** | **0.45x** | 34,960 B | **51,648 B** |
| **B3** | **100% 全树变长修改** (20 子项) | 13,159.6 | 9,728.6 | **11,325.4** | **1.16x** | 15,880 B | **15,747 B** |
| **B4** | 8 字节极小包全读+全变长改 | 545.4 | 403.5 | **456.9** | **1.19x** | 536 B | **2,816 B** |

### 3.2 Java Track II：复用输出 Buffer（Compat vs Lower-Level `RawOrderEnvelope`）

| 场景编号 | 工作负载描述 | 官方 Imm | 官方 ReuseB | 手写 Eager | Compat Imm | Compat ReuseB | **Lower `Raw`** | `ReuseB` 加速比 | **`Raw` 加速比** | 官方 ReuseB 分配 | Compat ReuseB 分配 | **Lower `Raw` 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (无输出) | 9,432.6 | 8,427.7 | 5,843.7 | 2,437.7 | 683.2 | **1,167.5** | 12.34x | **7.22x** | 34,312 B | 0 B | **0 B** |
| **A2** | 读 2 字段 + 转发 (FP0) | 18,134.8 | 17,994.8 | 17,205.8 | 955.9 | 795.5 | **626.8** | 22.62x | **28.71x** | 38,424 B | 0 B | **0 B** |
| **A3** | 改 3 同长字段 (FP1) | 18,150.8 | 17,997.8 | 17,599.7 | 1,102.6 | 1,167.5 | **1,082.8** | 15.42x | **16.62x** | 38,424 B | 0 B | **0 B** |
| **A4** | 改变长字符串 + City (FP2) | 18,199.6 | 18,119.8 | 17,622.2 | 1,433.1 | 1,118.5 | **1,427.9** | 16.20x | **12.69x** | 38,424 B | 80 B | **0 B** |
| **A5** | 100 项列表改第 42 项 | 18,979.4 | 19,068.0 | 17,414.6 | 1,506.5 | 1,586.2 | **1,577.9** | 12.02x | **12.08x** | 39,040 B | 208 B | **0 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 17,907.2 | 18,326.9 | 17,285.0 | 1,256.5 | 1,055.8 | **812.9** | 17.36x | **22.55x** | 38,424 B | 0 B | **0 B** |
| **B1** | 从零构建 (10 子项，独立 `clear()`) | 1,557.3 | 1,411.8 | 988.7 | 1,454.3 | 1,355.1 | **1,267.1** | 1.04x | **1.11x** | 3,392 B | 1,144 B | **0 B** |
| **B2** | **100% 全树读取 (含 `String`)** | 6,634.5 | 7,016.9 | 5,257.8 | 14,866.2 | 18,437.0 | **15,159.5** | 0.38x | **0.46x** | 34,312 B | 19,545 B | **15,520 B** |
| **B3** | **100% 全树变长改** | 4,149.0 | 4,224.1 | 3,635.8 | 9,736.0 | 9,787.8 | **4,344.8** | 0.43x | **0.97x** | 13,920 B | 6,176 B | **1,280 B** |
| **B4** | 8 字节全读+全变长改 | 403.0 | 261.0 | 192.0 | 393.2 | 228.9 | **194.8** | 1.14x | **1.34x** | 224 B | 0 B | **0 B** |

### 3.3 Java JMH 1.37 (`-prof gc`) 横向对比：Official PB vs QuickBuffers 1.4 vs LightProto 0.9.3 vs `lazy-proto`

为验证 `lazy-proto` 相对社区主流高性能零分配 Java Protobuf 引擎的真实定位，我们在统一 JMH 1.37 基准 [JavaProtoJmhBenchmark.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/JavaProtoJmhBenchmark.java)（`Mode.AverageTime`，`-prof gc`，5 库语义由 [CrossLibraryParityTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/CrossLibraryParityTest.java) 全量对拍通过）下，以完全对等的**对象复用 + 预分配输出缓冲复用**口径对比以下 5 套实现：
- **Official PB (`3.25.5`)**：`Builder.clear().mergeFrom()` + `CodedOutputStream.newInstance(outBuf, 0, size)`；
- **QuickBuffers (`1.4`)**：`clearQuick()` + 复用 `ProtoSource.newArraySource()` / `ProtoSink.newArraySink()`（默认代码生成跳过未知字段 `#50/#51`）；
- **LightProto (`0.9.3`)**：复用消息对象 + 复用 Netty 堆内 `Unpooled.wrappedBuffer` 读写（`CLEAR_RETAIN_MAX = 64 KB`，延迟解码 String/Bytes 但**急切解析全部子消息与重复项**，且跳过未知字段 `#50/#51`）；
- **`lazy-proto` Compat (`ReuseB`)**：`LazyOrderEnvelope.Builder.reuse()` + `writeToBuffer(outBuf, 0)`（保留全部未知字段）；
- **`lazy-proto` Raw (`lazy-proto-core`)**：零依赖 `RawOrderEnvelope.wrap()` + `writeToBuffer(outBuf, 0)`（保留全部未知字段）。

| 场景 | 工作负载描述 | Official PB (`ns / B`) | QuickBuffers 1.4 (`ns / B`) | LightProto 0.9.3 (`ns / B`) | **`lazy-proto` Compat (`ns / B`)** | **`lazy-proto` Raw (`ns / B`)** | `Raw` vs OffPB / QB / LP |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | :---: |
| **A1** | 只读 3 字段 (12.4 KB，无序列化) | 5,714.1 / 34,312 B | 3,094.1 / 0 B | 2,458.6 / 0 B | **590.9 / 0 B** | **580.0 / 0 B** | **9.85x / 5.33x / 4.24x** |
| **A2** | 读 2 字段 + 原样转发 (12.4 KB 含未知字段) | 18,175.5 / 38,392 B | 4,873.0 / 0 B | 3,864.0 / 0 B | **645.0 / 0 B** | **627.3 / 0 B** | **28.97x / 7.77x / 6.16x** |
| **A2b** | 原样转发 (去除未知字段，命中 LightProto `_cachedSize`) | — | — | 3,913.8 / 0 B | — | **622.4 / 0 B** | **— / — / 6.29x** |
| **A3** | 改 3 个定长/同长字段 + 序列化 (FP1) | 18,568.4 / 38,392 B | 4,923.5 / 0 B | 4,047.1 / 0 B | **709.3 / 0 B** | **670.8 / 0 B** | **27.68x / 7.34x / 6.03x** |
| **A4** | 改变长字符串 + 嵌套 City + 序列化 (FP2) | 20,575.8 / 38,360 B | 5,018.9 / 0 B | 4,114.7 / 0 B | **1,066.6 / 80 B** | **976.2 / 0 B** | **21.08x / 5.14x / 4.22x** |
| **A5** | 100 项列表改第 42 项 + 序列化 | 19,337.6 / 39,008 B | 4,964.9 / 0 B | 4,058.4 / 0 B | **1,232.0 / 200 B** | **882.7 / 0 B** | **21.91x / 5.62x / 4.60x** |
| **A6** | 网关脱敏删 `auth_token` + 转发 | 18,798.9 / 38,360 B | 4,850.1 / 0 B | 3,842.9 / 0 B | **872.1 / 0 B** | **741.3 / 0 B** | **25.36x / 6.54x / 5.18x** |
| **B1** | 从零构建新消息 (10 子项，0% 复用) | 1,012.2 / 2,992 B | 489.0 / 0 B | **289.5 / 0 B** | 1,032.2 / 1,064 B | 623.5 / 0 B | 1.62x / 0.78x / 0.46x |
| **B2** | 100% 全树读取 (100 子项，含 `String` 解码) | 6,500.5 / 34,312 B | 5,307.5 / 15,520 B | **4,608.2 / 15,520 B** | 10,421.3 / 18,720 B | 8,629.1 / 15,520 B | 0.75x / 0.62x / 0.53x |
| **B3** | 100% 全树变长修改 (20 子项，0% 区间复用) | 3,657.9 / 12,544 B | 1,608.9 / 0 B | **1,127.7 / 0 B** | 8,288.7 / 4,736 B | 2,891.4 / 0 B | 1.27x / 0.56x / 0.39x |
| **B4** | 8 字节极小包全读 + 全变长修改 | 45.2 / 152 B | **18.1 / 0 B** | 21.3 / 0 B | 62.0 / 0 B | 52.0 / 0 B | 0.87x / 0.35x / 0.41x |

> **关键架构洞察（帕累托前沿分工）**：
> 1. **稀疏读、路由转发、局部修改与脱敏（A1–A6）**：即便面对 Java 生态中最快的两套零分配引擎，**`lazy-proto` Raw 依然领先 LightProto `4.22x ~ 6.29x`、领先 QuickBuffers `5.14x ~ 7.77x`、领先官方 `protobuf-java` `9.85x ~ 28.97x`**，且保持严格的 **`0 B/op`**（零 Netty 依赖，并完整保留未知字段 `#50/#51`）。`A2b`（无未知字段）证实 LightProto 的 `3.9 µs` 开销并非来自未知字段，而是源于其必须急切解析全部 100 个 `LineItem` 子消息并逐字段重新编码；`lazy-proto` 仅浅扫描顶层并整段拼接未修改字节区间。
> 2. **全树构筑、全量读改与极小包（B1–B4）**：当 100% 字段均被访问或改写（无字节区间可复用）时，LightProto 与 QuickBuffers 凭借扁平生成的强类型字段（无 Tape/Overlay 间接层）明显占优（快于 `lazy-proto` Raw `1.3x ~ 2.9x`）；`lazy-proto` Raw 在 B1/B3 仍快于官方 PB `1.27x ~ 1.62x` 且保持 `0 B/op`（B2 仅分配必选的 `15,520 B` `java.lang.String`，与 QuickBuffers/LightProto 完全一致）。
> 3. **JMH `-prof gc` 实测价值**：在首轮 JMH 剖析中，`-prof gc` 捕获到 `WireTape.scanRange` 与 `ImmutableWireBase.scanRange` 中每次扫描捕获 Lambda 导致的 `16~24 B/op` 隐性分配；将其改为缓存的 `RawRecordSink` 方法引用后，Java `Raw` 在全部 9 个非 `String` 解码场景（含 B3 全树变长改）均回落至严格的 **`0 B/op`**。

### 3.4 开放生态标准基准与选择性交叉点扫描（JMH 1.37 `-prof gc`）

为避免“自建 Schema 偏差”，我们在 [proto/open_benchmarks.proto](proto/open_benchmarks.proto) 与 [OpenBenchmarksJmh.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/OpenBenchmarksJmh.java)（4 库语义由 [OpenBenchmarksParityTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/OpenBenchmarksParityTest.java) 全量对拍验证）中引入了 4 套社区公认开放基准：
1. **O1 — `jvm-serializers` 经典 `MediaContent`**（241 B 小包，验证小消息边界）；
2. **O2 — Apache Pulsar `MessageMetadata`**（489 B，含 10 条 `KeyValue` 属性 — LightProto 自身主打的官方基准场景）；
3. **O3 — OpenTelemetry `ExportTraceServiceRequest`**（13.4 KB，4 层深嵌套：1 `ResourceSpans` → 2 `ScopeSpans` → 50 `Span` × 6 属性 — 云原生可观测网关/脱敏代理真实负载）；
4. **O4 — 修改选择性交叉点扫描（Selectivity Sweep）**：在 12.4 KB 无未知字段 `OrderEnvelope` 上精确扫描修改 `1% / 10% / 25% / 50% / 100%` 列表项的延迟曲线。

> 注：O1–O3 中 `lazy-proto` 直接使用**无代码生成的通用 `RawWireView`** 迎战官方 `protoc 3.25.5`、QuickBuffers `1.4` 与 LightProto `0.9.3` 的代码生成类；O4 使用强类型 `RawOrderEnvelope`。此外，O3 实测中 `-prof gc` 还捕获并修复了 `RawWireView.resetLocal()` 误清空重复子视图池 `repSubViews[r]` 的问题，使 O3a–O3c 回落至与 QuickBuffers/LightProto 完全齐平的 `0 B / 64 B`，O3d 堆分配从 `438 KB` 降至与两者逐字节一致的 `37,888 B`。

| 开放场景 | 负载描述 | Official PB 3.25.5 (`ns / B`) | QuickBuffers 1.4 (`ns / B`) | LightProto 0.9.3 (`ns / B`) | **`lazy-proto` Raw (`ns / B`)** | `Lazy` vs OffPB / QB / LP |
| :--- | :--- | ---: | ---: | ---: | ---: | :---: |
| **O1a** | `jvm-serializers` `MediaContent` 局部读 (241 B) | 246.1 / 1,200 B | 135.2 / 56 B | **98.3 / 56 B** | 144.9 / 56 B | 1.70x / 0.93x / 0.68x |
| **O1b** | `jvm-serializers` `MediaContent` 全量读 (241 B) | 255.7 / 1,200 B | **215.9 / 616 B** | 226.0 / 616 B | 459.3 / 616 B | 0.56x / 0.47x / 0.49x |
| **O1c** | `MediaContent` 改 `title` + 序列化 (241 B) | 641.6 / 1,528 B | 256.4 / 112 B | **171.3 / 0 B** | 202.2 / 0 B | 3.17x / 1.27x / 0.85x |
| **O2a** | Apache Pulsar `MessageMetadata` Broker 路由 (489 B) | 541.5 / 2,560 B | 210.9 / 64 B | **160.8 / 64 B** | **164.4 / 64 B** | **3.29x / 1.28x / 0.98x** |
| **O2b** | Pulsar Broker 盖章 `replicated_from` + 转发 (489 B) | 1,295.2 / 3,088 B | 351.4 / 0 B | 341.9 / 0 B | **217.2 / 0 B** | **5.96x / 1.62x / 1.57x** |
| **O2c** | Pulsar 全量读取全部 10 条 `KeyValue` 属性 | 540.8 / 2,560 B | **389.9 / 1,344 B** | 421.3 / 1,344 B | 885.6 / 1,344 B | 0.61x / 0.44x / 0.48x |
| **O3a** | **OpenTelemetry** 按 `service.name` 路由转发 (13.4 KB) | 36,674.9 / 101,585 B | 12,209.5 / 64 B | 10,914.9 / 64 B | **320.6 / 64 B** | **114.39x / 38.08x / 34.04x** |
| **O3b** | **OpenTelemetry** 注入 Resource 属性 + 转发 (13.4 KB) | 37,062.7 / 103,249 B | 12,734.9 / 0 B | 11,173.4 / 0 B | **488.6 / 0 B** | **75.86x / 26.06x / 22.87x** |
| **O3c** | **OpenTelemetry** 4 层深嵌套脱敏 1 条 PII Span 属性 | 37,852.0 / 104,145 B | 12,794.9 / 0 B | 10,796.2 / 0 B | **985.1 / 0 B** | **38.42x / 12.99x / 10.96x** |
| **O3d** | OpenTelemetry 后端全量遍历 50 Spans × 6 属性 | 20,240.9 / 89,936 B | 14,331.0 / 37,888 B | **13,038.3 / 37,888 B** | 29,372.0 / 37,889 B | 0.69x / 0.49x / 0.44x |
| **O4 (1%)** | Selectivity Sweep：改 **1 / 100** 项 + 序列化 | 19,094.5 / 37,904 B | 5,011.9 / 0 B | 3,868.0 / 0 B | **852.0 / 0 B** | **22.41x / 5.88x / 4.54x** |
| **O4 (10%)** | Selectivity Sweep：改 **10 / 100** 项 + 序列化 | 19,082.3 / 39,488 B | 5,136.5 / 0 B | 3,924.3 / 0 B | **1,604.9 / 0 B** | **11.89x / 3.20x / 2.45x** |
| **O4 (25%)** | Selectivity Sweep：改 **25 / 100** 项 + 序列化 | 18,547.6 / 42,128 B | 4,888.5 / 0 B | 4,096.6 / 0 B | **2,812.0 / 0 B** | **6.60x / 1.74x / 1.46x** |
| **O4 (50%)** | Selectivity Sweep：改 **50 / 100** 项 + 序列化 | 19,471.6 / 46,528 B | 5,848.5 / 0 B | **3,900.1 / 0 B** | 4,929.6 / 0 B | **3.95x / 1.19x** / 0.79x |
| **O4 (100%)** | Selectivity Sweep：改 **100 / 100** 项 + 序列化 | 15,848.7 / 55,328 B | 6,304.7 / 0 B | **3,986.9 / 0 B** | 8,764.2 / 0 B | **1.81x** / 0.72x / 0.45x |

> **定量选型边界（直接由 O1–O4 测得）**：
> - **消息体量门槛**：在 `241 B` 极小包（O1）上 LightProto 占优；一旦达到 **`~489 B` 含 10 条属性的 Pulsar 元数据（O2b）**，通用无代码生成的 `RawWireView` 已以 `217 ns (0 B)` 反超 LightProto (`1.57x`) 与 QuickBuffers (`1.62x`)。
> - **深嵌套批处理放大效应（O3）**：在 4 层深嵌套 OpenTelemetry 追踪批次（13.4 KB / 50 Spans）上，因为无需解析其余 49 个 Span 与 299 条属性，通用 `RawWireView` 实现 **`11x–34x` 领先 LightProto、`13x–38x` 领先 QuickBuffers、`38x–114x` 领先官方 PB**。
> - **修改选择性交叉点（O4）**：当单条消息中修改比例 **`< 40%`** 时，`lazy-proto` 严格快于 LightProto；**`< 65%`** 时快于 QuickBuffers；即使 **100% 列表项全部改写**，仍以 `0 B/op` 领先官方 `protobuf-java` **`1.81x`**。

---

## 4. C++20 实测基准数据（官方 `libprotobuf 3.21.12` vs 手写 Eager vs C++20 Lower-Level `OrderEnvelopeView`）

| 场景 | 工作负载描述 | PB-Fresh (`ns`) | PB-Reuse (`ns`) | HandEager (`ns`) | **Lazy-Fresh (`ns`)** | **Lazy-WrapAlloc (`ns`)** | **Lazy-Reuse (`ns`)** | `vs PB-Fresh` | `vs PB-Reuse` | PB-Reuse 分配 | **Lazy-Reuse 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (12.4 KB) | 13,306.6 | 5,179.6 | 5,243.7 | **352.8** | **344.8** | **344.8** | **37.72x** | **15.02x** | 214 B | **0 B** |
| **A2** | 读 2 + 转发 (FP0) | 16,115.8 | 8,551.6 | 6,243.3 | **521.0** | **508.8** | **403.0** | **30.93x** | **21.22x** | 214 B | **0 B** |
| **A3** | 3 同长/定长字段 (FP1) | 16,170.7 | 8,569.1 | 6,173.7 | **551.1** | **538.2** | **437.7** | **29.34x** | **19.58x** | 214 B | **0 B** |
| **A4** | 变长字符串 + City (FP2) | 16,483.8 | 8,646.9 | 6,219.2 | **645.3** | **630.7** | **520.5** | **25.54x** | **16.61x** | 244 B | **0 B** |
| **A5** | 100 项改第 42 项 | 16,301.8 | 8,500.7 | 6,205.1 | **738.7** | **718.6** | **526.7** | **22.07x** | **16.14x** | 235 B | **0 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 16,181.1 | 8,508.7 | 6,278.4 | **598.5** | **586.1** | **481.8** | **27.04x** | **17.66x** | 214 B | **0 B** |
| **B1** | 从零构建 (10 子项) | 1,088.5 | 707.6 | 248.4 | **474.3** | **345.2** | **287.0** | **2.29x** | **2.47x** | 156 B | **0 B** |
| **B2** | 100% 全树读取 (100 项) | 13,216.9 | 5,363.4 | 5,215.0 | **1,271.9** | **1,263.4** | **1,263.4** | **10.39x** | **4.25x** | 214 B | **0 B** |
| **B3** | 100% 全树变长改 (20 项) | 4,403.9 | 2,349.1 | 1,582.7 | **1,792.8** | **1,576.1** | **1,379.6** | **2.46x** | **1.70x** | 889 B | **0 B** |
| **B4** | 8 字节全读+全变长改 | 52.1 | 43.7 | 38.9 | **54.2** | **48.2** | **38.1** | **0.96x** | **1.15x** | 0 B | **0 B** |

> 注：C++ `Lazy-Fresh` 在只读场景（A1、B2）与 8 字节小包（B4）上均为 **`0 B/op`**（其余场景仅分配输出 `std::string`）；`Lazy-Reuse` 在 **全部 10 个场景中均为严格的 `0 B/op`**。

### 4.1 C++20 横向对比与开放生态标准基准（`PB-Reuse` vs `PB-Arena` vs Mapbox `protozero v1.8.2` vs `lazy-proto`）

为在 C++ 侧同样覆盖真实开放 Schema 与社区极致零拷贝流式竞品，我们在 [open_benchmark_main.cpp](cpp/bench/open_benchmark_main.cpp) 中以对称 `observe_output` 防 DCE/DSE 与全局 `operator new` 堆分配统计，对以下 4 套 C++ 引擎完成全量语义对拍与基准评测：
- **`PB-Reuse`**（`libprotobuf 3.21.12` 复用对象 + `SerializeToArray`）；
- **`PB-Arena`**（`libprotobuf 3.21.12` + 预分配 512 KB 内存块的 `google::protobuf::Arena` + `SerializeToArray`）；
- **`protozero v1.8.2`**（Mapbox 头文件零拷贝流式读写器 `pbf_reader` + `fixed_size_buffer_adaptor`；**手写最优实现**：A1/A2/O1a/O2a/O3a 采用提前终止流扫描，A3–A6/O1c/O2b/O3b/O3c/O4 对未修改子消息直接通过 `get_view()` + `add_bytes()` 原始拷贝，全程 `0 B/op`）；
- **`lazy-proto` C++20**：核心 `OrderEnvelope`（A1–A6、B2、O4）使用 `OrderEnvelopeView`，开放基准 O1–O3 直接使用**无代码生成的通用 `WireView`**（升级 `slot_count_` 就地复用槽位后，**全部 22 项场景均实现严格 `0 B/op`**）。

#### 表 4.1a：核心 `OrderEnvelope` (12.4 KB) 4 引擎横向对比

| 场景 | 工作负载描述 | `PB-Reuse` (`ns / B`) | `PB-Arena` (`ns / B`) | `protozero v1.8.2` (`ns / B`) | **`lazy-proto` C++20 (`ns / B`)** | `Lazy` vs Reuse / Arena / PZ |
| :--- | :--- | ---: | ---: | ---: | ---: | :---: |
| **A1** | 只读 3 字段 (12.4 KB，无序列化) | 5,300.1 / 214 B | 8,246.4 / 9,830 B | **21.1 / 0 B** | 348.7 / 0 B | **15.20x / 23.65x** / 0.06x |
| **A2** | 读 2 字段 + 原样转发 (FP0) | 8,395.4 / 214 B | 11,369.7 / 9,830 B | **89.7 / 0 B** | 410.0 / 0 B | **20.48x / 27.73x** / 0.22x |
| **A3** | 改 3 个定长/同长字段 (FP1) | 8,462.7 / 214 B | 11,352.8 / 9,830 B | 1,126.0 / 0 B | **435.2 / 0 B** | **19.44x / 26.08x / 2.59x** |
| **A4** | 改变长字符串 + City (FP2) | 8,698.5 / 244 B | 11,549.9 / 9,860 B | 1,125.3 / 0 B | **554.8 / 0 B** | **15.68x / 20.82x / 2.03x** |
| **A5** | 100 项列表改第 42 项 | 8,555.7 / 235 B | 11,626.3 / 9,851 B | 1,051.8 / 0 B | **591.4 / 0 B** | **14.47x / 19.66x / 1.78x** |
| **A6** | 脱敏删 `auth_token` + 转发 | 8,596.0 / 214 B | 11,644.1 / 9,830 B | 980.9 / 0 B | **475.2 / 0 B** | **18.09x / 24.50x / 2.06x** |
| **B2** | 100% 全树读取 (100 子项) | 5,448.0 / 214 B | 8,504.7 / 9,830 B | 4,522.4 / 0 B | **1,902.1 / 0 B** | **2.86x / 4.47x / 2.38x** |

#### 表 4.1b：C++20 开放生态标准基准（O1 `MediaContent` / O2 Pulsar / O3 OpenTelemetry / O4 Selectivity Sweep）

| 开放场景 | 负载描述 | `PB-Reuse` (`ns / B`) | `PB-Arena` (`ns / B`) | `protozero v1.8.2` (`ns / B`) | **`lazy-proto` C++20 (`ns / B`)** | `Lazy` vs Reuse / Arena / PZ |
| :--- | :--- | ---: | ---: | ---: | ---: | :---: |
| **O1a** | `MediaContent` 局部读 (241 B) | 315.5 / 383 B | 355.9 / 105 B | **39.7 / 0 B** | 94.2 / 0 B | **3.35x / 3.78x** / 0.42x |
| **O1b** | `MediaContent` 全量读 (241 B) | 325.9 / 383 B | 373.8 / 105 B | **179.3 / 0 B** | 234.3 / 0 B | **1.39x / 1.60x** / 0.77x |
| **O1c** | `MediaContent` 改 `title` + 序列化 | 495.1 / 447 B | 561.4 / 169 B | **118.8 / 0 B** | 141.9 / 0 B | **3.49x / 3.96x** / 0.84x |
| **O2a** | Apache Pulsar Broker 路由 (489 B) | 482.0 / 0 B | 730.2 / 280 B | **38.8 / 0 B** | 144.7 / 0 B | **3.33x / 5.05x** / 0.27x |
| **O2b** | Pulsar Broker 盖章 + 转发 (489 B) | 806.3 / 0 B | 1,057.8 / 280 B | 193.2 / 0 B | **165.2 / 0 B** | **4.88x / 6.40x / 1.17x** |
| **O2c** | Pulsar 全量读 10 条属性 (489 B) | 483.8 / 0 B | 763.8 / 280 B | **219.8 / 0 B** | 508.4 / 0 B | 0.95x / **1.50x** / 0.43x |
| **O3a** | **OpenTelemetry** 路由转发 (13.4 KB) | 26,750.7 / 1,074 B | 34,951.2 / 6,581 B | **118.6 / 0 B** | 219.4 / 0 B | **121.91x / 159.28x** / 0.54x |
| **O3b** | **OpenTelemetry** 注入 Resource 属性 | 26,855.7 / 1,114 B | 35,291.8 / 6,621 B | **339.3 / 0 B** | 388.2 / 0 B | **69.18x / 90.91x** / 0.87x |
| **O3c** | **OpenTelemetry** 4 层深嵌套脱敏 PII | 26,852.5 / 1,074 B | 35,281.8 / 6,581 B | 772.5 / 0 B | **700.1 / 0 B** | **38.35x / 50.39x / 1.10x** |
| **O3d** | OpenTelemetry 全量遍历 50 Spans × 6 | 16,961.2 / 1,074 B | 25,648.4 / 6,581 B | **9,805.7 / 0 B** | 14,108.1 / 0 B | **1.20x / 1.82x** / 0.70x |
| **O4 (1%)** | Selectivity Sweep：改 **1 / 100** 项 | 8,540.8 / 169 B | 12,759.5 / 9,737 B | 993.0 / 0 B | **575.3 / 0 B** | **14.85x / 22.18x / 1.73x** |
| **O4 (10%)** | Selectivity Sweep：改 **10 / 100** 项 | 8,633.3 / 331 B | 12,780.8 / 9,899 B | 1,376.3 / 0 B | **1,076.1 / 0 B** | **8.02x / 11.88x / 1.28x** |
| **O4 (25%)** | Selectivity Sweep：改 **25 / 100** 项 | 8,945.2 / 601 B | 13,082.8 / 10,169 B | 2,010.0 / 0 B | **1,880.9 / 0 B** | **4.76x / 6.96x / 1.07x** |
| **O4 (50%)** | Selectivity Sweep：改 **50 / 100** 项 | 9,221.0 / 1,051 B | 13,369.2 / 10,619 B | **3,054.5 / 0 B** | 3,202.3 / 0 B | **2.88x / 4.17x** / 0.95x |
| **O4 (100%)** | Selectivity Sweep：改 **100 / 100** 项 | 10,046.6 / 1,951 B | 14,053.1 / 11,519 B | **5,148.4 / 0 B** | 5,880.7 / 0 B | **1.71x / 2.39x** / 0.88x |

> **C++20 架构对决洞察**：
> 1. **`lazy-proto` vs `libprotobuf` (`PB-Reuse` & `PB-Arena`)**：在 O1–O4 与 A1–A6/B2 几乎全线大胜（OpenTelemetry O3a–O3c 达 **`38x–122x` vs Reuse、`50x–159x` vs Arena**；即使在 O3d 全量遍历与 O4 100% 全改下依然领先 `1.20x–1.71x` 且全程 `0 B/op`）。注：在 `libprotobuf 3.21` 中，长于 SSO 的 `std::string` 仍会从系统堆分配，因此每轮重置 `PB-Arena` 反而慢于保留字符串容量的 `PB-Reuse`。
> 2. **`lazy-proto` vs Mapbox `protozero v1.8.2`**：
>    - **前部提前退出只读（A1/A2/O1a/O2a/O3a）**：手写 `protozero` 流扫描可在读到前几个字段后立刻 `break`（`21–119 ns`），但前提是放弃 Protobuf“后出现重复 Tag 覆盖前者（last-one-wins）”语义且仅支持单遍顺序访问；`lazy-proto` 扫描顶层至 EOF 以保证严格 Protobuf 语义与任意随机读写。
>    - **局部修改 + 拼接写回（A3–A6、O2b、O3c、O4 ≤40%）与全树读取（B2）**：即便 `protozero` 手写了数十行嵌套流式重写器并直接 `get_view()` 透传未修改子消息，它仍必须逐个 Tag 驱动 `pbf_writer` 写入；`lazy-proto` 凭借 FP1 原地修补与 FP2 连续区间整段 `memcpy`，在 **A3–A6 上反超 `protozero` `1.78x–2.59x`**、在 **B2 上反超 `2.38x`**、在 **O2b Pulsar 盖章（489 B）与 O3c 4 层 OTel PII 脱敏上反超 `1.10x–1.17x`**，且调用方仅需 1 行链式随机读写 API 而非数十行状态机样板代码。

---

## 5. 横向优劣势对比与工程选型指南（Pros & Cons）

### 5.1 架构维度对比矩阵

| 对比维度 | 官方 `protobuf` (Java / C++) | QuickBuffers 1.4 (Java) | LightProto 0.9.3 (Java) | Mapbox `protozero` 1.8.2 (C++) | **`lazy-proto`** (Java Core/Compat + C++20) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **核心执行模型** | 全树急切解析 + 不可变 (Java) / 堆对象 (C++) | 全树急切解析至扁平可复用可变对象 | 结构急切解析 + Netty `ByteBuf` 延迟 String/Bytes 视图 | 单遍零拷贝流式 `pbf_reader` / `pbf_writer` | **分层浅扫描 + 内联 128-bit `WireTape` + 4 级字节拼接** |
| **稳态堆分配** | 高（Java `34–104 KB/op`；C++ `214 B–10 KB/op`） | **`0 B/op`**（`clearQuick()`） | **`0 B/op`**（`< 64 KB` `CLEAR_RETAIN_MAX`） | **`0 B/op`**（`fixed_size_buffer_adaptor`） | **`0 B/op`**（Java `Raw` 与 C++20 全场景复用/修改） |
| **未知字段保留** | 自动保留（`UnknownFieldSet`） | **默认丢弃**（`skipField`） | **丢弃**（`skipUnknownField`） | 手动在 `default:` 分支透传 | **自动完整保留**（未修改字节区间原样拼接） |
| **代码生成与生态** | 成熟 `protoc`（全量 Protobuf 特性） | 成熟 `protoc-gen-quickbuf`（含 JSON） | 成熟 Maven 插件（含 gRPC Stub） | 纯头文件，**无代码生成**（手写 `switch` 状态机） | **尚无 `protoc` 插件**（`RawWireView`/`WireView` 通用免生成；强类型模型手写） |
| **运行时依赖** | `protobuf-java` / `libprotobuf` | `quickbuf-runtime`（极轻量） | **强依赖 Netty `netty-buffer`**（+ `sun.misc.Unsafe`） | 纯头文件（零依赖） | **零依赖**（`lazy-proto-core` & C++20）；Compat 依赖 `protobuf-java` |
| **线程安全模型** | 不可变快照（Java） | 单线程可变复用 | 单线程可变复用 | 单线程游标 | **单线程复用**（Core/C++）；**不可变快照**（Java Compat） |

### 5.2 `lazy-proto` 的核心优势（Pros）

1. **稀疏读、路由透传、脱敏与局部修改（修改比例 `< 40%`）断层领先**：
   - **Java**：在 `OrderEnvelope` A1–A6 上领先 **LightProto `4.2x–6.3x`**、领先 **QuickBuffers `5.1x–7.8x`**、领先官方 PB **`9.9x–29x`**；在 4 层深嵌套 OpenTelemetry 追踪批次（13.4 KB / 50 Spans）上进一步放大至 **`11x–34x` vs LightProto、`13x–38x` vs QuickBuffers、`38x–114x` vs 官方 PB**。
   - **C++20**：在网关/代理场景领先 **`libprotobuf` Reuse/Arena `15x–159x`**；即便对比手写零拷贝透传的 **`protozero`**，凭借 FP1 原地修补与 FP2 连续区间单次 `memcpy` 拼接，仍在 **A3–A6 领先 `1.78x–2.59x`**、**B2 领先 `2.38x`**、**O2b Pulsar 盖章（489 B）与 O3c 4 层 OTel 脱敏领先 `1.10x–1.17x`**。
2. **随机读写与链式修改 API，无流式状态机样板税**：对比 `protozero` 需手写 35 行嵌套 `while (r.next())` + RAII `PzWriter` 才能修改 4 层深嵌套的 OpenTelemetry PII 属性，`lazy-proto` 仅需 **1 行链式调用**且运行更快。
3. **默认前向兼容（未知字段与分段子消息保留）**：API 网关、代理与消息队列**不能静默丢弃新版客户端的未知字段**；QuickBuffers（默认）与 LightProto 均会丢弃未知字段，而 `lazy-proto` 以零额外开销天然原样透传。
4. **核心零第三方依赖且无 `Unsafe` 反射风险**：`lazy-proto-core` 与 C++20 头文件无任何外部依赖（不绑定 Netty，也不依赖 JDK 24+ JEP 498 告警的 `sun.misc.Unsafe` `String` 内部字段反射）。
5. **渐进式 Java 兼容迁移路径**：`lazy-proto-compat` 提供 `Message`/`Builder`/`OrBuilder` 兼容层与线程安全不可变快照，便于存量服务平滑迁移；热点链路可下沉至零依赖 `lazy-proto-core`。

### 5.3 `lazy-proto` 的局限与取舍（Cons）

1. **全树读写、从零构筑与极小包（Java B1–B4、O1、O3d）不占优**：当几乎 100% 字段均被访问/改写，或消息极小（`< 300 B`，如 241 B `MediaContent`）时，无未触及字节区间可供拼接，**LightProto 0.9.3** 与 **QuickBuffers 1.4** 凭借扁平生成的强类型字段快 `1.3x–2.9x`。在 12.4 KB 消息上，修改比例超过 **`~40%`**（对比 LightProto）或 **`~65%`**（对比 QuickBuffers）后，扁平急切引擎反超。
2. **C++ 纯前缀提前退出只读场景慢于 `protozero`（A1/A2/O1a/O2a/O3a）**：当目标字段恰位于字节流最前部且允许放弃 Protobuf 严格的“后出现重复 Tag 覆盖前者（last-one-wins）”语义与随机访问时，`protozero` 可扫描 20 字节即 `break`（`21–119 ns`）；`lazy-proto` 扫描完整顶层至 EOF 以保证严格 Protobuf 语义和任意随机读写。
3. **输入缓冲区生命周期耦合（GC Pinning 风险）**：与 LightProto（`materialize()` 前）及 `protozero` 类似，`lazy-proto` 持有原始输入缓冲区引用（`byte[]` / `const uint8_t*`）。调用方就地篡改输入数组会破坏视图；在 Java 中长期缓存一个源自 1 MB 大包的小视图会锚定整块 1 MB 底层数组（需显式拷贝/序列化脱钩）。官方 PB 与 QuickBuffers 在解析时即与输入缓冲解耦。
4. **默认延迟校验（Deferred Validation）**：未访问的子消息在 `wrap()`/`parseFrom()` 阶段不展开，损坏的深层嵌套字节仅在访问时或显式调用 `validateDeep()`/`validate_deep()` 时报错（全量校验会抵消懒解析收益）。
5. **代码生成与 Protobuf 全生态特性尚待补齐**：当前缺少 `protoc-gen-lazyproto` 插件（通用 `RawWireView`/`WireView` 按字段号访问，较强类型生成代码更易出错且全树读略慢）；尚未提供 `oneof` 互斥辅助、`map<K,V>` 类型化视图、`packed` 重复基础类型拼接辅助、JSON/TextFormat、Descriptor 反射、gRPC Stub 及 Compat 层 `equals`/`hashCode`。
6. **Java Compat 层重写入开销较高**：`lazy-proto-compat` 在 A1–A6 表现优异（`0–200 B/op`），但 B1–B3 全量构筑/改写受限于外挂 COW `WireOverlay` 与 `ByteString` 桥接，产生 `1–5 KB/op` 分配且明显慢于 `lazy-proto-core` 与 LightProto/QuickBuffers。

### 5.4 工程选型速查表

| 典型业务场景 | Java 推荐 | C++ 推荐 | 核心原因 |
| :--- | :--- | :--- | :--- |
| **API 网关、RPC 代理、消息路由、PII 脱敏、字段富化、OTel Collector**（`> 400 B`、修改 `< 40%`、必须保留未知字段） | **`lazy-proto`** (`Raw` / `Compat`) | **`lazy-proto`** (`WireView` / 强类型) | 比 LightProto 快 `4x–34x`、比 `libprotobuf` 快 `15x–159x`，`0 B/op`，自动保留未知字段 |
| **极致低延迟纯前缀只读路由**（仅读前 1–2 字段、无修改、确定无重复覆盖 Tag） | **`lazy-proto` Raw** / **LightProto** | **Mapbox `protozero`** | 流式游标读完前缀即提前终止（`21–40 ns`） |
| **全树业务逻辑、DB/缓存反序列化、`< 300 B` 极小 RPC、从零构筑消息** | **LightProto 0.9.3**（允许 Netty）或 **QuickBuffers 1.4**（无 Netty） | **`protozero`**（只读）/ **`libprotobuf` Reuse**（可变 DOM） | 100% 字段触及时，扁平生成字段免除 Tape 间接开销 |
| **全规范 Protobuf 特性**（反射/Descriptor、`Any`、JSON、gRPC、团队通用安全） | **官方 `protobuf-java`** | **官方 `libprotobuf`** | 生态完整、无边界特性裁剪 |

> **下一步最高收益演进方向**：实现轻量级 **`protoc-gen-lazyproto` 代码生成器**（同步补齐 `packed` repeated 基础类型与 `oneof` 支持），让任意 `.proto` 无需手写即可直接获得 `RawOrderEnvelope` / `OrderEnvelopeView` 级性能。

---

## 6. 核心结论与仓库索引

1. **Java 分层效果与真实性能边界**：将 `io.lazyproto.core`（`RawOrderEnvelope` / `RawWireView`）从 `Message`/`Builder` 兼容税中剥离后，Java Lower Level 在 **A1–A6、B1、B4 复用场景全部达到 `0 B/op`**（A2–A6 最高达 `28.71x`，B3 追平官方至 `0.97x`）；在需要 100% 解码全量 JVM `String` 的 **B2 劣势场景**中，Lower Level（`15,160 ns, 15,520 B`）优于 Compat（`18,437 ns, 19,545 B`），但仍明显慢于 Eager 单遍顺序反序列化（`0.46x`），符合“按需懒解析适用稀疏读写、全树 `String` 读取不占优”的架构预期。
2. **C++ 纯 Lower Level 在严格对等生命周期下全面领先**：即便在每轮新建对象（`Lazy-Fresh` vs `PB-Fresh`）并开启输出防 DCE 保活后，A1–A6 仍实现 **`22.07x ~ 37.72x`** 领先，B1–B3 实现 **`2.29x ~ 10.39x`** 领先；复用模式下全场景 **`0 B/op`** 且全部优于 `libprotobuf` Reuse（含 B4 极小包 `1.15x`）。
3. **仓库索引**：
   - 统一协议定义：[order_envelope.proto](proto/order_envelope.proto)、[open_benchmarks.proto](proto/open_benchmarks.proto)
   - Java Lower Level（`lazy-proto-core`，零 `protobuf-java` 依赖）：[WireTape.java](lazy-proto-core/src/main/java/io/lazyproto/core/WireTape.java)、[ScratchUtf8Arena.java](lazy-proto-core/src/main/java/io/lazyproto/core/ScratchUtf8Arena.java)、[RawOrderEnvelope.java](lazy-proto-core/src/main/java/io/lazyproto/core/RawOrderEnvelope.java)、[RawWireView.java](lazy-proto-core/src/main/java/io/lazyproto/core/RawWireView.java)
   - Java Compat & 测试（`lazy-proto-compat` / `lazy-proto-benchmarks`）：[pom.xml](pom.xml)、[JavaProtoJmhBenchmark.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/JavaProtoJmhBenchmark.java)、[OpenBenchmarksJmh.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/OpenBenchmarksJmh.java)、[CrossLibraryParityTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/CrossLibraryParityTest.java)、[OpenBenchmarksParityTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/OpenBenchmarksParityTest.java)、[LazyProtoBenchmarkSuite.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/LazyProtoBenchmarkSuite.java)、[LazyProtoCorrectnessTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/LazyProtoCorrectnessTest.java)、[StatefulModelFuzzTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/StatefulModelFuzzTest.java)、[LowerLevelFuzzAndRegressionTest.java](lazy-proto-benchmarks/src/test/java/io/lazyproto/LowerLevelFuzzAndRegressionTest.java)
   - C++ Lower Level（模块化头文件）：[Makefile](cpp/Makefile)、[wire.hpp](cpp/include/lazyproto/wire.hpp)、[tape.hpp](cpp/include/lazyproto/tape.hpp)、[wire_view.hpp](cpp/include/lazyproto/wire_view.hpp)、[order_envelope_view.hpp](cpp/include/lazyproto/order_envelope_view.hpp)、[test_main.cpp](cpp/tests/test_main.cpp)、[benchmark_main.cpp](cpp/bench/benchmark_main.cpp)、[open_benchmark_main.cpp](cpp/bench/open_benchmark_main.cpp)
