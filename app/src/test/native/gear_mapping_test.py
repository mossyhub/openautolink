#!/usr/bin/env python3
"""Exercise verbatim gear writer bodies with real SensorBatch serialization.

Only IO scheduling/transport are host doubles; enum setters and protobuf wire
round trips are real. JNI takes raw VHAL gear; legacy headless JSON explicitly
promises AA gear ordinals (not VHAL). No Android/session lifecycle coverage.
Use the same Linux proto build and protobuf prefix as ev_connector_mapping_test.py.
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
APP = ROOT / "app/src/main/cpp/jni_session.cpp"
BRIDGE = ROOT / "bridge/openautolink/headless/src/live_session.cpp"


def body(path, signature):
    text = path.read_text()
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening
    while depth:
        end += 1
        depth += (text[end] == "{") - (text[end] == "}")
    return text[start:end + 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proto-build-dir", required=True, type=Path)
    parser.add_argument("--protobuf-prefix", required=True, type=Path)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--debug-protobuf", action="store_true")
    args = parser.parse_args()
    out = args.artifact_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    includes = "\n".join(sorted({line for p in (APP, BRIDGE)
        for line in p.read_text().splitlines()
        if line.startswith('#include "openautolink/') and 'gear' in line}))
    harness = r'''
#include <aap_protobuf/service/sensorsource/message/SensorBatch.pb.h>
#include <climits>
#include <functional>
#include <iostream>
#include <memory>
#include <optional>
#include <vector>
INCLUDES
namespace AA = aap_protobuf::service::sensorsource::message;
struct Promise { template<class A, class B> void then(A, B) {} };
namespace aasdk::channel {
struct SendPromise { template<class T> static auto defer(T&) {
    return std::make_shared<Promise>();
} };
}
struct Io {
    std::vector<std::function<void()>> queue;
    template<class F> void post(F f) { queue.emplace_back(f); }
    void drain() { for (auto& f : queue) f(); queue.clear(); }
};
struct Channel {
    std::vector<AA::SensorBatch> sent;
    template<class P> void sendSensorEventIndication(const AA::SensorBatch& b, P) {
        std::string bytes;
        AA::SensorBatch decoded;
        if (!b.SerializeToString(&bytes) || !decoded.ParsePartialFromString(bytes)) std::abort();
        sent.push_back(decoded);
    }
};
struct JniSession {
    bool streaming_ = true;
    Channel* sensorChannel_;
    Io* ioService_;
    int strandValue = 0;
    int* strand_ = &strandValue;
    void sendGearSensor(int gear);
};
struct HeadlessSensorHandler {
    Channel* channel_;
    int strand_ = 0;
    void sendGear(int gear);
};
APP_BODY
BRIDGE_BODY
int main() {
    // Pin against the actual linked production schema, not surrogate enum values.
    static_assert(AA::GEAR_NEUTRAL == 0 && AA::GEAR_DRIVE == 100 &&
                  AA::GEAR_PARK == 101 && AA::GEAR_REVERSE == 102 &&
                  AA::GEAR_1 == 1 && AA::GEAR_10 == 10);
    int failures = 0, checks = 0;
    auto expect = [&](const char* path, int raw, std::optional<int> expected,
                      const Channel& channel) {
        ++checks;
        bool ok = expected ? channel.sent.size() == 1 : channel.sent.empty();
        if (expected && channel.sent.size() == 1) {
            const auto& b = channel.sent.front();
            ok = b.gear_data_size() == 1 && b.gear_data(0).has_gear() &&
                 static_cast<int>(b.gear_data(0).gear()) == *expected;
        }
        if (!ok) {
            ++failures;
            if (failures <= 24) {
                std::cerr << "FAIL " << path << " input=" << raw << " expected=";
                if (expected) std::cerr << *expected; else std::cerr << "no-send";
                std::cerr << " sends=" << channel.sent.size();
                if (!channel.sent.empty() && channel.sent.front().gear_data_size())
                    std::cerr << " gear=" << channel.sent.front().gear_data(0).gear();
                std::cerr << '\n';
            }
        }
    };
    auto app = [&](int raw, std::optional<int> expected) {
        Channel channel; Io io; JniSession s{true, &channel, &io};
        s.sendGearSensor(raw); io.drain(); expect("jni", raw, expected, channel);
    };
    // Park is the original regression: raw4 must be AA101, never AA fourth gear.
    app(4, 101); app(1, 0); app(2, 102); app(8, 100);
    for (int gear = 1; gear <= 9; ++gear) app(1 << (gear + 3), gear);
    // Exhaust unknown, all combinations of the thirteen legal VHAL bits and
    // out-of-domain AA ordinals. Not a bitmask: only one exact enum is legal.
    for (int raw = 0; raw < 16384; ++raw) {
        std::optional<int> expected;
        switch (raw) {
            case 1: expected = 0; break; case 2: expected = 102; break;
            case 4: expected = 101; break; case 8: expected = 100; break;
            default:
                for (int gear = 1; gear <= 9; ++gear)
                    if (raw == (1 << (gear + 3))) expected = gear;
        }
        app(raw, expected);
    }
    for (int raw : {-1, -2, -4, -8, INT_MIN, INT_MAX, 16384, 65536}) app(raw, {});
    for (int raw = -1; raw <= 103; ++raw) {
        Channel channel; HeadlessSensorHandler s{&channel};
        s.sendGear(raw);
        std::optional<int> expected;
        if ((raw >= 0 && raw <= 10) || (raw >= 100 && raw <= 102)) expected = raw;
        expect("headless-aa", raw, expected, channel);
    }
    for (int raw : {INT_MIN, INT_MAX, 128, 256, 4096}) {
        Channel channel; HeadlessSensorHandler s{&channel}; s.sendGear(raw);
        expect("headless-aa", raw, {}, channel);
    }
    for (bool streaming : {false, true}) {
        Channel channel; Io io;
        JniSession s{streaming, streaming ? nullptr : &channel, &io};
        s.sendGearSensor(4); io.drain(); expect("inactive", 4, {}, channel);
    }
    std::cout << "gear writer checks=" << checks << " failures=" << failures << '\n';
    return failures ? 1 : 0;
}
'''
    harness = harness.replace("INCLUDES", includes).replace("APP_BODY", body(APP,
        "void JniSession::sendGearSensor(")).replace("BRIDGE_BODY", body(BRIDGE,
        "void HeadlessSensorHandler::sendGear("))
    source = out / "gear_writers.cpp"
    source.write_text(harness)
    exe = out / "gear_writers_test"
    lib = args.protobuf_prefix / "lib/x86_64-linux-gnu"
    command = ["g++", "-std=c++20", "-O0", "-Wall", "-Wextra", "-Werror",
        *([] if args.debug_protobuf else ["-DNDEBUG"]),
        "-I" + str(ROOT / "native/include"),
        "-I" + str(args.proto_build_dir / "generated"),
        "-I" + str(args.protobuf_prefix / "include"), str(source),
        str(args.proto_build_dir / "liblab-proto.a"), "-L" + str(lib),
        "-Wl,-rpath," + str(lib), "-lprotobuf", "-pthread", "-o", str(exe)]
    print("COMPILE", " ".join(command), flush=True)
    subprocess.run(command, check=True)
    env = os.environ.copy()
    env["LD_LIBRARY_PATH"] = str(lib) + ":" + env.get("LD_LIBRARY_PATH", "")
    result = subprocess.run([str(exe)], env=env)
    print("TEST_EXIT=" + str(result.returncode), flush=True)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
