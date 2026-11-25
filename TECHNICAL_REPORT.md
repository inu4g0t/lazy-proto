# Lazy-Proto 技术总结报告：Lazy 反序列化与输入复用序列化的架构、跨语言实现及真实官方基线对比

> **报告概述**：本报告基于统一 Schema [proto/order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)，对 `lazy-proto`（Java 21 Architecture v3 接口兼容版 + C++20 硬件极限版）与 **真实官方 `protoc` 生成消息类**（`protoc 3.25.5` Java / `libprotobuf 3.21.12` C++）及 **手写精简 Eager 基线** 进行了同生命周期、同输出缓冲口径的交叉正确性验证与微基准评测。

---

## 1. 核心设计哲学与算法创新

传统 Protocol Buffers 运行时（无论是 Java 还是 C++）均遵循 **“全量反序列化 $\to$ 内存对象树修改 $\to$ 两阶段全量重序列化”** 的对称流水线。在微服务网关、RPC 路由、流式特征处理等 **“读少写少、大进大出”（Read-Few, Modify-Few, Passthrough-Most）** 场景中，90% 以上的 CPU 周期与内存带宽被浪费在从未被业务触碰的字段上。

`lazy-proto` 提出四级机制（Java Architecture v3 采用 `ImmutableWireBase` + 外挂 COW `WireOverlay` + `WireMergeEngine`）：
1. **Tier-0 $O(1)$ 零开销切片绑定**：记录 `(buf, rawOffset, rawLength)`，不扫描、不分配对象。
2. **Tier-1 单层非递归浅扫描（`ShallowScanner` + 64-bit SWAR Varint 跳过）**：利用 WireType 2 (`LEN`) 长度前缀以 $O(1)$ 跨越未访问的子消息、字符串与 `repeated` 列表，将物理边界记入紧凑 `FieldTape`。
3. **Tier-2 外挂增量 Overlay 与“已读未改不回写”状态分离**：所有读写隔离在外挂 `Overlay` 中，仅解码被读取的字段，且冻结构建后的快照 100% 不可变共享（多线程并发懒加载安全）。
4. **Tier-3 四级输入复用序列化引擎**：Fast-Path 0 未改直通、Fast-Path 1 跨层级同长原地打补丁、Fast-Path 2 连续原始区间合并拼接、Fast-Path 3 C++ 单遍投机长度回填。

---

## 2. 实验环境、版本与严格对等对比口径

### 2.1 软硬件与工具链版本
- **CPU**：AMD Ryzen 7 7840HS (Zen 4 架构，L1d 256KB / L2 8MB / L3 16MB)
- **操作系统**：Ubuntu 26.04 LTS (Linux x86_64)
- **Java 21 官方基线与编译器**：
  - JDK：`Eclipse Temurin OpenJDK 21.0.13.1-LTS`
  - 官方生成与运行时：`com.google.protobuf:protoc:3.25.5` + `protobuf-java 3.25.5`（经 `mvn test-compile` 由 [order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto) 生成 `io.lazyproto.bench.proto.OrderEnvelope`）
  - 手写 Eager 基线：[StandardOrderProto.java](/home/inu4g0t/t3work/lazy-proto/src/main/java/io/lazyproto/model/StandardOrderProto.java)（仅使用底层 `CodedInputStream`/`CodedOutputStream` 的简化 POJO 树，与真实 `protoc` 类严格区分）
- **C++20 官方基线与编译参数**：
  - 编译器与参数：`GCC 15.2.0` (`g++ -O3 -march=native -std=c++20 -Wall -Wextra -lprotobuf`)
  - 官方生成与链接库：`libprotoc 3.21.12` + `libprotobuf-dev 3.21.12-15ubuntu1.1`（动态链接系统 `-lprotobuf`）
  - 手写 Eager 基线：[std_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/std_proto.hpp)

### 2.2 对比口径分离原则（消除输出缓冲与生命周期不对称偏差）
- **Java Track I（同不可变生命周期 + 每次分配 `toByteArray()`）**：官方 PB / 手写 Eager / Lazy Imm 均走 `parseFrom -> toBuilder -> build -> toByteArray()`。
- **Java Track II（统一复用预分配输出缓冲 `reusableOutBuf`）**：
  - `OffImm` vs `LazyImm`：相同不可变快照生命周期 + `CodedOutputStream(outBuf)` vs `writeToBuffer(outBuf)`；
  - `OffRseB` vs `LazyRseB`：相同线程局部可复用 `Builder`（官方 `clear().mergeFrom(bytes)` vs `lazy.reuse(bytes)`）+ `reusableOutBuf`。
- **C++ 双口径对比**：
  - **Fresh+Alloc**：每轮新建对象 + 分配输出 `std::string`；
  - **Reuse+ReuseOutBuf**：复用已分配消息对象（官方 `ParseFromArray`/`Clear()` + `SerializeToArray(out_buf)`）vs `LazyOrderEnvelope`（栈结构 `wrap()` + `write_to_buffer(out_buf)`）。

主测试包：12.4 KB 复杂嵌套 `OrderEnvelope`（100 个 `LineItem` + 4 KB `payload` + 2 个未知字段 `#50, #51`）；B3 为 20 个 `LineItem`；B4 为 11 字节 3 整数极小包。

---

## 3. Java 21 实测基准数据（Track I & Track II）

### 3.1 Java Track I：同不可变生命周期 + `toByteArray()`

| 场景编号 | 工作负载描述 | 官方 `protoc` PB (`ns/op`) | 手写 Eager (`ns/op`) | **`lazy-proto` Imm (`ns/op`)** | `vs 官方 PB` | 官方 PB 堆分配 | **`lazy-proto` 堆分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 个字段 (12.4 KB，无序列化) | 8,649.8 | 8,072.4 | **1,981.4** | **4.37x** | 34,960 B | **3,595 B** |
| **A2** | 读 2 字段 + 原样转发 (FP0) | 19,895.9 | 18,883.6 | **1,565.2** | **12.71x** | 50,936 B | **15,760 B** |
| **A3** | 改 3 个定长/同长字段 (FP1) | 19,162.5 | 18,569.1 | **2,480.4** | **7.73x** | 51,312 B | **16,552 B** |
| **A4** | 改变长字符串与嵌套 City (FP2) | 19,029.5 | 19,534.7 | **3,242.7** | **5.87x** | 51,344 B | **16,659 B** |
| **A5** | 100 项列表改第 42 项 | 19,858.9 | 18,108.3 | **3,190.5** | **6.22x** | 52,640 B | **17,061 B** |
| **A6** | 脱敏删 1 个字段 + 转发 | 21,737.5 | 18,298.6 | **2,208.8** | **9.84x** | 51,088 B | **16,133 B** |
| **B1** | 从零构建新消息 (10 子项) | 2,177.2 | 1,690.0 | **2,820.2** | **0.77x** | 3,728 B | **6,528 B** |
| **B2** | **100% 全树读取** (100 子项) | 5,990.3 | 5,302.3 | **14,236.1** | **0.42x** | 34,960 B | **51,648 B** |
| **B3** | **100% 全树变长修改** (20 子项) | 8,453.9 | 6,985.2 | **13,822.9** | **0.61x** | 15,880 B | **15,808 B** |
| **B4** | 11 字节极小包全读+全变长改 | 554.1 | 251.6 | **472.7** | **1.17x** | 536 B | **2,816 B** |

### 3.2 Java Track II：统一复用输出 Buffer（`outBuf`）+ Immutable / Reusable Builder

| 场景编号 | 工作负载描述 | 官方 Imm (`ns`) | 官方 ReuseB (`ns`) | 手写 Eager (`ns`) | **`lazy` Imm (`ns`)** | **`lazy` ReuseB (`ns`)** | `Imm` 加速比 | `ReuseB` 加速比 | 官方 ReuseB 分配 | **`lazy` ReuseB 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (无输出) | 8,649.8 | 8,169.9 | 8,072.4 | **1,981.4** | **887.2** | **4.37x** | **9.21x** | 34,312 B | **0 B** |
| **A2** | 读 2 字段 + 转发 (FP0) | 18,396.7 | 18,313.2 | 17,173.3 | **863.1** | **693.6** | **21.32x** | **26.40x** | 38,424 B | **0 B** |
| **A3** | 改 3 同长字段 (FP1) | 18,315.0 | 18,319.9 | 17,339.8 | **1,005.8** | **1,018.1** | **18.21x** | **17.99x** | 38,424 B | **0 B** |
| **A4** | 改变长字符串 + City (FP2) | 18,381.1 | 18,349.8 | 17,405.2 | **1,381.4** | **1,069.9** | **13.31x** | **17.15x** | 38,424 B | **80 B** |
| **A5** | 100 项列表改第 42 项 | 19,306.5 | 19,392.2 | 17,753.3 | **1,596.8** | **2,024.5** | **12.09x** | **9.58x** | 39,040 B | **208 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 18,291.3 | 18,474.0 | 17,260.5 | **1,231.7** | **1,213.7** | **14.85x** | **15.22x** | 38,424 B | **0 B** |
| **B1** | 从零构建 (10 子项) | 1,322.9 | 1,322.9 | 1,006.3 | **1,350.6** | **1,350.6** | **0.98x** | **0.98x** | 2,344 B | **5,848 B** |
| **B2** | **100% 全树读取** | 5,990.3 | 6,860.4 | 5,302.3 | **14,236.1** | **12,268.3** | **0.42x** | **0.56x** | 34,312 B | **19,552 B** |
| **B3** | **100% 全树变长改** | 4,058.9 | 3,989.1 | 3,301.8 | **9,606.5** | **9,853.6** | **0.42x** | **0.40x** | 13,920 B | **6,176 B** |
| **B4** | 11 字节全读+全变长改 | 380.8 | 221.0 | 268.4 | **311.9** | **215.7** | **1.22x** | **1.02x** | 224 B | **0 B** |

---

## 4. C++20 实测基准数据（官方 `libprotobuf 3.21.12` vs 手写 Eager vs Lazy-Proto C++20）

| 场景 | 工作负载描述 | 官方 PB Fresh (`ns`) | 官方 PB Reuse (`ns`) | 手写 Eager (`ns`) | **Lazy Alloc (`ns`)** | **Lazy Reuse (`ns`)** | `vs PB-Fresh` | `vs PB-Reuse` | PB-Reuse 分配 | **Lazy-Reuse 分配** |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A1** | 只读 3 字段 (12.4 KB) | 13,388.4 | 5,137.5 | 5,272.0 | **223.5** | **223.5** | **59.89x** | **22.98x** | 214 B | **0 B** |
| **A2** | 读 2 + 转发 (FP0) | 16,308.5 | 8,370.4 | 6,295.5 | **375.8** | **298.4** | **43.39x** | **28.05x** | 214 B | **0 B** |
| **A3** | 3 同长/定长字段 (FP1) | 16,235.5 | 8,490.2 | 6,395.4 | **408.9** | **317.9** | **39.70x** | **26.70x** | 214 B | **0 B** |
| **A4** | 变长字符串 + City (FP2) | 16,701.7 | 8,451.7 | 6,394.1 | **462.8** | **370.5** | **36.09x** | **22.81x** | 244 B | **0 B** |
| **A5** | 100 项改第 42 项 | 16,230.3 | 8,473.2 | 6,349.0 | **446.3** | **358.0** | **36.37x** | **23.67x** | 235 B | **0 B** |
| **A6** | 脱敏删 1 字段 + 转发 | 16,419.7 | 9,800.2 | 6,367.5 | **422.6** | **342.9** | **38.85x** | **28.58x** | 214 B | **0 B** |
| **B1** | 从零构建 (10 子项) | 1,074.6 | 710.1 | 254.9 | **426.6** | **430.5** | **2.52x** | **1.65x** | 156 B | **0 B** |
| **B2** | 100% 全树读取 (100 项) | 13,171.8 | 5,232.5 | 5,393.7 | **1,100.5** | **1,100.5** | **11.97x** | **4.75x** | 214 B | **0 B** |
| **B3** | 100% 全树变长改 (20 项) | 4,593.5 | 2,410.7 | 1,723.7 | **1,016.7** | **968.5** | **4.52x** | **2.49x** | 889 B | **0 B** |
| **B4** | 11 字节全读+全变长改 | 49.8 | 40.6 | 26.9 | **30.2** | **24.7** | **1.65x** | **1.64x** | 0 B | **0 B** |

> 注：C++ `PB-Fresh` 在 A1–A6 上触发 `24 KB ~ 36 KB/op` 堆分配；`PB-Reuse` 虽复用了节点容器但仍因未知字段等分配 `214 ~ 889 B/op`；而 `Lazy-Reuse` 在 **全部 10 个场景中均为严格的 `0 B/op`（Zero `malloc`/`new`）**。

---

## 5. 核心结论与客观定位

1. **为什么之前必须做真实 `protoc` + 同口径对比？**
   - Java 中，先前拿官方类 `toByteArray()` 对抗 `lazy.writeToBuffer()` 放大了新建场景（`B1`/`B3`/`B4`）的相对表现；一旦两边同样使用 `outBuf` 复用，**A1–A6 依然有 9.21x ~ 26.40x（ReuseB）与 4.37x ~ 21.32x（Imm）的巨大优势**，而 `B2` / `B3` 全量读写场景的 2-Pass 代价（`0.40x ~ 0.56x`）也更真实客观。
   - C++ 中，接入真实 `libprotobuf 3.21.12` 后，连先前相对精简手写 struct 落后的 `B1（从零构建）` 对真实 `libprotobuf` 也实现了 **`1.65x ~ 2.52x`** 领先（而相对手写 Eager 依然清晰标明为 `254.9 ns` vs `430.5 ns`）。
2. **仓库索引**：
   - 统一协议定义：[order_envelope.proto](/home/inu4g0t/t3work/lazy-proto/proto/order_envelope.proto)
   - Java 测试与基准：[pom.xml](/home/inu4g0t/t3work/lazy-proto/pom.xml)、[LazyProtoBenchmarkSuite.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoBenchmarkSuite.java)、[LazyProtoCorrectnessTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/LazyProtoCorrectnessTest.java)、[StatefulModelFuzzTest.java](/home/inu4g0t/t3work/lazy-proto/src/test/java/io/lazyproto/StatefulModelFuzzTest.java)
   - C++ 构建与基准：[Makefile](/home/inu4g0t/t3work/lazy-proto/cpp/Makefile)、[lazy_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/lazy_proto.hpp)、[std_proto.hpp](/home/inu4g0t/t3work/lazy-proto/cpp/std_proto.hpp)、[benchmark_main.cpp](/home/inu4g0t/t3work/lazy-proto/cpp/benchmark_main.cpp)
