#!/usr/bin/env python3
"""Exercise verbatim native control-error/abort bodies with boundary fakes.

This is source-body behavior coverage, not whole-JNI or vehicle execution.
The real callback bodies and abort election are extracted; JNI reporting and
worker launch are boundary fakes. --source supports an unchanged-test RED replay.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def extract(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parents[4]
    parser.add_argument("--source", type=Path, default=root / "app/src/main/cpp/jni_session.cpp")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    text = args.source.read_text()
    bodies = "\n".join(extract(text, signature) for signature in (
        "void JniSession::onChannelError(",
        "void JniSession::reportChannelError(",
        "void JniSession::triggerAbort(",
    ))
    fixture = r'''
#include <atomic>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <mutex>
#include <string>
#include <unordered_map>
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)
using jstring = const char*;
struct JNIEnv {
 jstring NewStringUTF(const char* x) { return x; }
 void CallVoidMethod(void*, void*, jstring) {}
 void DeleteLocalRef(jstring) {}
};
namespace aasdk::error {
 enum class ErrorCode { OPERATION_ABORTED = 30, OTHER = 99 };
 struct Error {
  ErrorCode code;
  ErrorCode getCode() const { return code; }
  const char* what() const { return "fixture transport error"; }
 };
}
struct JniSession {
 std::atomic<bool> streaming_{false}, stopped_{false}, aborted_{false}, byeByeSent_{false};
 std::mutex errorMu_;
 std::unordered_map<std::string, int64_t> lastLogPerChannel_;
 int64_t firstErrorAtMs_ = 0;
 int errorCount_ = 0;
 struct { void* onError = nullptr; } cbMethods_;
 void* callbackRef_ = nullptr;
 int stops = 0;
 std::string stopReason;
 int64_t nowMs() { return 10000; }
 JNIEnv* getEnv(bool& attached) { attached = false; return nullptr; }
 void releaseEnv(bool) {}
 void setStopReason(const std::string& reason) { stopReason = reason; }
 void requestStop() { ++stops; }
 void onChannelError(const aasdk::error::Error&);
 void reportChannelError(const char*, const aasdk::error::Error&);
 void triggerAbort(const std::string&);
};
BODIES
int main(int argc, char** argv) {
 assert(argc == 2);
 const std::string mode = argv[1];
 const aasdk::error::Error terminal{aasdk::error::ErrorCode::OPERATION_ABORTED};
 const aasdk::error::Error other{aasdk::error::ErrorCode::OTHER};
 JniSession old, successor;
 if (mode == "startup-terminal") {
  old.onChannelError(terminal);
  assert(old.stops == 1 && "one terminal startup control error must retire without waiting 15 seconds");
  assert(old.aborted_ && !old.stopReason.empty());
 } else if (mode == "duplicate-terminal") {
  for (int i = 0; i < 12; ++i) old.onChannelError(terminal);
  assert(old.stops == 1 && "terminal startup failure must elect exactly one retirement");
 } else if (mode == "live-single") {
  old.streaming_ = true;
  old.onChannelError(terminal);
  assert(old.stops == 0 && "do not replace a streaming session on one generic channel error");
 } else if (mode == "live-escalation") {
  old.streaming_ = true;
  for (int i = 0; i < 5; ++i) old.onChannelError(terminal);
  assert(old.stops == 1 && "retain existing live multi-channel error escalation");
 } else if (mode == "stopped-owner") {
  old.stopped_ = true;
  old.onChannelError(terminal);
  assert(old.stops == 0 && "explicitly retiring owner must not start another abort");
 } else if (mode == "graceful-owner") {
  old.byeByeSent_ = true;
  old.onChannelError(terminal);
  assert(old.stops == 0 && "preserve graceful-shutdown reason and retirement ownership");
 } else if (mode == "nonterminal-startup") {
  old.onChannelError(other);
  assert(old.stops == 0 && "do not broaden immediate retirement to every protocol error");
 } else if (mode == "successor-isolation") {
  old.onChannelError(terminal);
  assert(old.stops == 1);
  successor.streaming_ = true;
  old.onChannelError(terminal);
  assert(successor.stops == 0 && successor.streaming_);
 } else { return 2; }
 std::cout << "PASS " << mode << '\n';
}
'''.replace("BODIES", bodies)
    harness = args.output / "startup_transport_failure.cpp"
    harness.write_text(fixture)
    binary = args.output / "startup_transport_failure"
    command = [os.environ.get("CXX", "g++"), "-std=c++17", "-pthread", str(harness), "-o", str(binary)]
    subprocess.run(command, check=True, timeout=120)
    results = []
    for case in ("startup-terminal", "duplicate-terminal", "live-single", "live-escalation",
                 "stopped-owner", "graceful-owner", "nonterminal-startup", "successor-isolation"):
        result = subprocess.run([str(binary), case], text=True, capture_output=True, timeout=10)
        results.append({"case": case, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
        print(case, result.returncode, result.stdout.strip(), result.stderr.strip(), flush=True)
    receipt = {"source": str(args.source), "source_sha256": hashlib.sha256(args.source.read_bytes()).hexdigest(),
               "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "bodies_sha256": hashlib.sha256(bodies.encode()).hexdigest(), "cases": results}
    (args.output / "results.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return int(any(case["exit"] != 0 for case in results))


if __name__ == "__main__":
    raise SystemExit(main())
