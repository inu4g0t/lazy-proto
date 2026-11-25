CXX ?= g++
PROTOC ?= protoc
CXXFLAGS ?= -O3 -march=native -std=c++20 -Wall -Wextra
PROTO_DIR = ../proto
GEN_DIR = gen

GEN_SRCS = $(GEN_DIR)/order_envelope.pb.cc
GEN_HDRS = $(GEN_DIR)/order_envelope.pb.h

.PHONY: all clean run

all: benchmark_cpp

$(GEN_SRCS) $(GEN_HDRS): $(PROTO_DIR)/order_envelope.proto
	@mkdir -p $(GEN_DIR)
	$(PROTOC) --proto_path=$(PROTO_DIR) --cpp_out=$(GEN_DIR) $<

benchmark_cpp: benchmark_main.cpp $(GEN_SRCS) lazy_proto.hpp std_proto.hpp
	$(CXX) $(CXXFLAGS) -I. -I$(GEN_DIR) benchmark_main.cpp $(GEN_SRCS) -lprotobuf -o $@

run: benchmark_cpp
	./benchmark_cpp

clean:
	rm -rf $(GEN_DIR) benchmark_cpp
