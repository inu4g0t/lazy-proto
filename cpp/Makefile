CXX ?= g++
PROTOC ?= protoc
CXXFLAGS ?= -O3 -march=native -std=c++20 -Wall -Wextra
PROTO_DIR = ../proto
GEN_DIR = gen
PROTOZERO_INC ?= third_party/protozero/include
INCLUDES = -Iinclude -Ibench -I$(GEN_DIR)

GEN_ORDER_SRCS = $(GEN_DIR)/order_envelope.pb.cc
GEN_ORDER_HDRS = $(GEN_DIR)/order_envelope.pb.h
GEN_OPEN_SRCS = $(GEN_DIR)/open_benchmarks.pb.cc
GEN_OPEN_HDRS = $(GEN_DIR)/open_benchmarks.pb.h
GEN_SRCS = $(GEN_ORDER_SRCS)
HEADERS = include/lazyproto/wire.hpp include/lazyproto/tape.hpp include/lazyproto/wire_view.hpp include/lazyproto/order_envelope_view.hpp lazy_proto.hpp bench/std_proto.hpp bench/fixture.hpp

.PHONY: all clean run run-open fetch-deps test test-ndebug test-asan

all: test_cpp benchmark_cpp

$(GEN_ORDER_SRCS) $(GEN_ORDER_HDRS): $(PROTO_DIR)/order_envelope.proto
	@mkdir -p $(GEN_DIR)
	$(PROTOC) --proto_path=$(PROTO_DIR) --cpp_out=$(GEN_DIR) $<

$(GEN_OPEN_SRCS) $(GEN_OPEN_HDRS): $(PROTO_DIR)/open_benchmarks.proto
	@mkdir -p $(GEN_DIR)
	$(PROTOC) --proto_path=$(PROTO_DIR) --cpp_out=$(GEN_DIR) $<

fetch-deps:
	@if [ ! -f "$(PROTOZERO_INC)/protozero/pbf_reader.hpp" ]; then \
		mkdir -p third_party/protozero && \
		curl -sL https://github.com/mapbox/protozero/archive/refs/tags/v1.8.2.tar.gz | tar -xz --strip-components=1 -C third_party/protozero; \
	fi

test_cpp: tests/test_main.cpp $(GEN_ORDER_SRCS) $(HEADERS)
	$(CXX) $(CXXFLAGS) $(INCLUDES) tests/test_main.cpp $(GEN_ORDER_SRCS) -lprotobuf -o $@

benchmark_cpp: bench/benchmark_main.cpp $(GEN_ORDER_SRCS) $(HEADERS)
	$(CXX) $(CXXFLAGS) $(INCLUDES) bench/benchmark_main.cpp $(GEN_ORDER_SRCS) -lprotobuf -o $@

benchmark_open_cpp: bench/open_benchmark_main.cpp $(GEN_ORDER_SRCS) $(GEN_OPEN_SRCS) $(HEADERS) fetch-deps
	$(CXX) $(CXXFLAGS) $(INCLUDES) -I$(PROTOZERO_INC) bench/open_benchmark_main.cpp $(GEN_ORDER_SRCS) $(GEN_OPEN_SRCS) -lprotobuf -o $@

run: benchmark_cpp
	./benchmark_cpp

run-open: benchmark_open_cpp
	./benchmark_open_cpp

test: test_cpp
	./test_cpp

test-ndebug: tests/test_main.cpp $(GEN_ORDER_SRCS) $(HEADERS)
	$(CXX) $(CXXFLAGS) -DNDEBUG $(INCLUDES) tests/test_main.cpp $(GEN_ORDER_SRCS) -lprotobuf -o test_cpp_ndebug
	./test_cpp_ndebug

test-asan: tests/test_main.cpp $(GEN_ORDER_SRCS) $(HEADERS)
	$(CXX) -O1 -g -std=c++20 -Wall -Wextra -fsanitize=address,undefined -fno-omit-frame-pointer $(INCLUDES) tests/test_main.cpp $(GEN_ORDER_SRCS) -lprotobuf -o test_cpp_asan
	./test_cpp_asan

clean:
	rm -rf $(GEN_DIR) test_cpp test_cpp_ndebug test_cpp_asan benchmark_cpp benchmark_open_cpp benchmark_cpp_ndebug benchmark_cpp_asan
