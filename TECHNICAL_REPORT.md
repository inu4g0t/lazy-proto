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
- **Java 21 官方基线**：`Eclipse Temurin OpenJDK 21.0.13.1-LTS`，`com.google.protobuf:protoc:3.25.5` + `protobuf-java 3.25.5`；手写 Eager 基线为 [StandardOrderProto.java](src/main/java/io/lazyproto/model/StandardOrderProto.java)。
- **C++20 官方基线**：`GCC 15.2.0` (`-O3 -march=native -std=c++20 -Wall -Wextra -lprotobuf`)，`libprotoc 3.21.12` + `libprotobuf-dev 3.21.12`；手写 Eager 基线为 [std_proto.hpp](cpp/std_proto.hpp)。

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

---

## 5. 核心结论

1. **Java 分层效果与真实性能边界**：将 `io.lazyproto.core`（`RawOrderEnvelope` / `RawWireView`）从 `Message`/`Builder` 兼容税中剥离后，Java Lower Level 在 **A1–A6、B1、B4 复用场景全部达到 `0 B/op`**（A2–A6 最高达 `28.71x`，B3 追平官方至 `0.97x`）；在需要 100% 解码全量 JVM `String` 的 **B2 劣势场景**中，Lower Level（`15,160 ns, 15,520 B`）优于 Compat（`18,437 ns, 19,545 B`），但仍明显慢于 Eager 单遍顺序反序列化（`0.46x`），符合“按需懒解析适用稀疏读写、全树 `String` 读取不占优”的架构预期。
2. **C++ 纯 Lower Level 在严格对等生命周期下全面领先**：即便在每轮新建对象（`Lazy-Fresh` vs `PB-Fresh`）并开启输出防 DCE 保活后，A1–A6 仍实现 **`22.07x ~ 37.72x`** 领先，B1–B3 实现 **`2.29x ~ 10.39x`** 领先；复用模式下全场景 **`0 B/op`** 且全部优于 `libprotobuf` Reuse（含 B4 极小包 `1.15x`）。
3. **仓库索引**：
   - 统一协议定义：[order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)
   - Java Lower Level：[WireTape.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/WireTape.java)、[ScratchUtf8Arena.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/ScratchUtf8Arena.java)、[RawOrderEnvelope.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/RawOrderEnvelope.java)、[RawWireView.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/RawWireView.java)
   - Java Compat & 测试：[pom.xml](/home/inu4g0t/t3work/lazy-proto/pom.xml)、[LazyProtoBenchmarkSuite.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoBenchmarkSuite.java)、[LazyProtoCorrectnessTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoCorrectnessTest.java)、[StatefulModelFuzzTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/StatefulModelFuzzTest.java)
   - C++ Lower Level：[Makefile](/home/inu4g0t/t3work/lazy-proto/cpp/Makefile)、[lazy_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/lazy_proto.hpp)、[std_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/std_proto.hpp)、[benchmark_main.cpp](/home/inu4g0t/t3work/lazy-proto/cpp/benchmark_main.cpp)
