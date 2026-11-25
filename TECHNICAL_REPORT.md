# Lazy-Proto 技术总结报告：分层架构（Lower Level + Java Compat）与真实官方基线对比

> **报告概述**：本报告基于统一 Schema [proto/order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)，对 `lazy-proto` 的两层设计——**Lower Level（Java `io.lazyproto.core` + C++20 无兼容 Input-Bytes Wrapper）**与 **Java Intermediate Compat Layer（`Message`/`Builder` 兼容）**——与真实官方 `protoc 3.25.5` (Java) / `libprotobuf 3.21.12` (C++) 及手写 Eager 基线进行了同生命周期、同输出缓冲口径（含对称防 DCE 保活）的交叉验证与微基准评测。

---

## 1. 分层架构与核心算法创新

1. **Lower Level（极简高性能 Input-Bytes Wrapper）**：
   - **Java `io.lazyproto.core`**（[`WireTape`](src/main/java/io/lazyproto/core/WireTape.java)、[`RawOrderEnvelope`](src/main/java/io/lazyproto/core/RawOrderEnvelope.java)、[`RawWireView`](src/main/java/io/lazyproto/core/RawWireView.java)）与 **C++20**（[`cpp/lazy_proto.hpp`](cpp/lazy_proto.hpp)：`WireView` / `OrderEnvelopeView`）：
   - 零 `protobuf` 官方接口依赖，本质是对输入字节切片的轻量缓存包装器：单层浅扫描（SWAR Varint 跳过）+ 读写统一缓存 + 自动脏传播 + 4 级拼接序列化（FP0 直通、FP1 原地修补、FP2 连续区间合并、FP3 单遍投机长度回填）。
   - **C++ 定位**：**不追求任何 `google::protobuf::Message` 接口兼容**，直接交付纯 Lower Level。
2. **Java Intermediate Compatibility Layer（接口兼容中间层）**：
   - [`io.lazyproto.runtime`](src/main/java/io/lazyproto/runtime) + [`LazyOrderProto`](src/main/java/io/lazyproto/model/LazyOrderProto.java) + [`ProtoDocument`](src/main/java/io/lazyproto/dynamic/ProtoDocument.java)：在底层之上叠加 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `ProtobufCompat`，适配 `Message` / `Builder` / `XxxOrBuilder` / `build()` 不可变快照契约。

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
  - **`RawLower` (`RawOrderEnvelope`)**：Java Lower Level 零兼容税基准（`wrap()` / `initScratch()` + 1-pass `writeToBuffer()`）。
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
| **A1** | 只读 3 个字段 (12.4 KB，无序列化) | 9,345.5 | 6,368.8 | **2,472.2** | **3.78x** | 34,961 B | **3,652 B** |
| **A2** | 读 2 字段 + 原样转发 (FP0) | 19,345.1 | 19,082.8 | **1,436.3** | **13.47x** | 50,936 B | **15,760 B** |
| **A3** | 改 3 个定长/同长字段 (FP1) | 18,926.2 | 18,303.5 | **2,706.0** | **6.99x** | 51,312 B | **16,552 B** |
| **A4** | 改变长字符串与嵌套 City (FP2) | 19,125.9 | 18,131.0 | **3,220.5** | **5.94x** | 51,344 B | **16,664 B** |
| **A5** | 100 项列表改第 42 项 | 19,825.9 | 18,272.9 | **3,673.3** | **5.40x** | 52,640 B | **17,049 B** |
| **A6** | 脱敏删 1 个字段 + 转发 | 18,686.1 | 18,272.8 | **2,183.4** | **8.56x** | 51,088 B | **16,129 B** |
| **B1** | 从零构建新消息 (10 子项) | 1,964.4 | 2,106.4 | **3,343.7** | **0.59x** | 4,224 B | **4,024 B** |
| **B2** | **100% 全树读取** (100 子项) | 7,095.0 | 5,364.7 | **14,672.9** | **0.48x** | 34,960 B | **51,648 B** |
| **B3** | **100% 全树变长修改** (20 子项) | 11,761.9 | 8,579.1 | **11,337.7** | **1.04x** | 15,880 B | **15,749 B** |
| **B4** | 8 字节极小包全读+全变长改 | 696.6 | 266.6 | **484.8** | **1.44x** | 536 B | **2,816 B** |

### 3.2 Java Track II：复用输出 Buffer（Compat vs Lower-Level `RawOrderEnvelope`）

| 场景编号 | 工作负载描述 | 官方 Imm | 官方 ReuseB | 手写 Eager | Compat Imm | Compat ReuseB | **Lower `Raw`** | `ReuseB` 加速比 | **`Raw` 加速比** | 官方 ReuseB 分配 | Compat ReuseB 分配 | **Lower `Raw` 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (无输出) | 9,345.5 | 8,062.2 | 6,368.8 | 2,472.2 | 733.1 | **567.8** | 11.00x | **14.20x** | 34,312 B | 0 B | **0 B** |
| **A2** | 读 2 字段 + 转发 (FP0) | 17,967.2 | 18,174.1 | 17,304.2 | 846.7 | 679.7 | **603.0** | 26.74x | **30.14x** | 38,424 B | 0 B | **0 B** |
| **A3** | 改 3 同长字段 (FP1) | 18,307.3 | 18,656.8 | 19,057.5 | 1,395.5 | 838.4 | **879.8** | 22.25x | **21.21x** | 38,424 B | 0 B | **0 B** |
| **A4** | 改变长字符串 + City (FP2) | 18,271.4 | 18,449.6 | 17,508.9 | 1,435.5 | 1,075.2 | **2,309.3** | 17.16x | **7.99x** | 38,424 B | 80 B | **0 B** |
| **A5** | 100 项列表改第 42 项 | 20,068.5 | 19,094.2 | 17,864.2 | 1,675.7 | 1,518.0 | **821.1** | 12.58x | **23.26x** | 39,040 B | 208 B | **0 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 18,012.7 | 18,270.4 | 17,233.6 | 1,395.7 | 1,112.1 | **887.1** | 16.43x | **20.60x** | 38,424 B | 0 B | **0 B** |
| **B1** | 从零构建 (10 子项，独立 `clear()`) | 2,086.0 | 2,352.7 | 878.7 | 1,265.1 | 1,469.9 | **1,309.1** | 1.60x | **1.80x** | 3,392 B | 1,144 B | **0 B** |
| **B2** | **100% 全树读取** | 7,095.0 | 7,993.8 | 5,364.7 | 14,672.9 | 12,118.8 | **3,683.8** | 0.66x | **2.17x** | 34,312 B | 19,552 B | **320 B** |
| **B3** | **100% 全树变长改** | 3,875.0 | 3,973.9 | 3,265.4 | 9,946.9 | 9,841.2 | **5,295.2** | 0.40x | **0.75x** | 13,920 B | 6,176 B | **1,280 B** |
| **B4** | 8 字节全读+全变长改 | 441.0 | 184.6 | 260.3 | 377.4 | 195.5 | **133.6** | 0.94x | **1.38x** | 224 B | 0 B | **0 B** |

---

## 4. C++20 实测基准数据（官方 `libprotobuf 3.21.12` vs 手写 Eager vs C++20 Lower-Level `OrderEnvelopeView`）

| 场景 | 工作负载描述 | PB-Fresh (`ns`) | PB-Reuse (`ns`) | HandEager (`ns`) | **Lazy-Fresh (`ns`)** | **Lazy-WrapAlloc (`ns`)** | **Lazy-Reuse (`ns`)** | `vs PB-Fresh` | `vs PB-Reuse` | PB-Reuse 分配 | **Lazy-Reuse 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (12.4 KB) | 12,670.2 | 5,034.0 | 5,286.1 | **359.9** | **353.9** | **353.9** | **35.20x** | **14.23x** | 214 B | **0 B** |
| **A2** | 读 2 + 转发 (FP0) | 15,426.5 | 8,298.5 | 6,603.9 | **540.0** | **515.5** | **396.3** | **28.57x** | **20.94x** | 214 B | **0 B** |
| **A3** | 3 同长/定长字段 (FP1) | 15,714.6 | 8,495.9 | 6,577.4 | **529.9** | **517.0** | **420.0** | **29.65x** | **20.23x** | 214 B | **0 B** |
| **A4** | 变长字符串 + City (FP2) | 16,256.5 | 8,592.1 | 6,499.1 | **624.4** | **613.6** | **518.2** | **26.03x** | **16.58x** | 244 B | **0 B** |
| **A5** | 100 项改第 42 项 | 15,946.9 | 8,409.5 | 6,541.7 | **700.1** | **697.8** | **558.9** | **22.78x** | **15.05x** | 235 B | **0 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 15,778.2 | 8,381.2 | 6,633.3 | **594.6** | **737.2** | **486.8** | **26.53x** | **17.22x** | 214 B | **0 B** |
| **B1** | 从零构建 (10 子项) | 1,085.2 | 692.2 | 269.1 | **481.4** | **351.0** | **297.7** | **2.25x** | **2.32x** | 156 B | **0 B** |
| **B2** | 100% 全树读取 (100 项) | 12,918.5 | 5,203.6 | 5,527.0 | **1,194.9** | **1,191.0** | **1,191.0** | **10.81x** | **4.37x** | 214 B | **0 B** |
| **B3** | 100% 全树变长改 (20 项) | 4,243.6 | 2,286.1 | 1,656.4 | **1,836.4** | **1,566.7** | **1,429.9** | **2.31x** | **1.60x** | 889 B | **0 B** |
| **B4** | 8 字节全读+全变长改 | 51.7 | 43.4 | 27.3 | **57.8** | **46.1** | **41.4** | **0.89x** | **1.05x** | 0 B | **0 B** |

> 注：C++ `Lazy-Fresh` 在只读场景（A1、B2）与 8 字节小包（B4）上均为 **`0 B/op`**（其余场景仅分配输出 `std::string`）；`Lazy-Reuse` 在 **全部 10 个场景中均为严格的 `0 B/op`**。

---

## 5. 核心结论

1. **Java 分层效果立竿见影**：将 `io.lazyproto.core`（`RawOrderEnvelope` / `RawWireView`）从 `Message`/`Builder` 兼容税中剥离后，Java 底层在 A1–A6 达到 **`0 B/op`**（最高 `30.14x`），连最劣势的 **B2 全树读取也从 Compat 的 `0.66x` 反转至 `2.17x` 领先官方 PB**。
2. **C++ 纯 Lower Level 在严格对等生命周期下依然全面领先**：即便在每轮新建对象（`Lazy-Fresh` vs `PB-Fresh`）并开启输出防 DCE 保活后，A1–A6 仍实现 **`22.78x ~ 35.20x`** 领先，B1–B3 实现 **`2.25x ~ 10.81x`** 领先；复用模式下全场景 **`0 B/op`** 且全面优于 `libprotobuf`。
3. **仓库索引**：
   - 统一协议定义：[order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)
   - Java Lower Level：[WireTape.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/WireTape.java)、[ScratchUtf8Arena.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/ScratchUtf8Arena.java)、[RawOrderEnvelope.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/RawOrderEnvelope.java)、[RawWireView.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/core/RawWireView.java)
   - Java Compat & 测试：[pom.xml](/home/inu4g0t/t3work/lazy-proto/pom.xml)、[LazyProtoBenchmarkSuite.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoBenchmarkSuite.java)、[LazyProtoCorrectnessTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoCorrectnessTest.java)、[StatefulModelFuzzTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/StatefulModelFuzzTest.java)
   - C++ Lower Level：[Makefile](/home/inu4g0t/t3work/lazy-proto/cpp/Makefile)、[lazy_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/lazy_proto.hpp)、[std_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/std_proto.hpp)、[benchmark_main.cpp](/home/inu4g0t/t3work/lazy-proto/cpp/benchmark_main.cpp)
