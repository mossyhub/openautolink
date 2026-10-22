#!/usr/bin/env python3
"""Compile the real SDR connector insertion fragments against real protobuf.

Run on the build host (not the WebUI container):
  python3 app/src/test/native/ev_connector_mapping_test.py \
    --proto-build-dir /path/to/linux/generated-proto-build \
    --protobuf-prefix /path/to/linux/protobuf/prefix
The build directory must contain generated/ and liblab-proto.a built from the
canonical aasdk protos, as in aa-ev-lab. No Android archives or fake enum/sink.
This exercises the insertion fragments, not the surrounding JNI/bridge lifecycle.
"""
import argparse
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
APP = ROOT / "app/src/main/cpp/jni_session.cpp"
BRIDGE = ROOT / "bridge/openautolink/headless/src/live_session.cpp"


def connector_fragment(path, fuel_expression, end_marker):
    source = path.read_text()
    start = source.index("if (" + fuel_expression + ".empty())")
    # Skip the existing fuel if/else as a balanced block, not a copy of its logic.
    def end_block(pos):
        pos = source.index("{", pos)
        depth = 1
        while depth:
            pos += 1
            depth += (source[pos] == "{") - (source[pos] == "}")
        return pos + 1
    start = end_block(start)
    start = end_block(source.index("else", start))
    end = source.index(end_marker, start)
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proto-build-dir", type=Path, required=True)
    parser.add_argument("--protobuf-prefix", type=Path, required=True)
    parser.add_argument("--debug-protobuf", action="store_true",
                        help="Keep protobuf's debug enum assertions enabled")
    parser.add_argument("--artifact-dir", type=Path,
                        help="Retain generated harness and executable as evidence")
    args = parser.parse_args()
    proto = args.proto_build_dir.resolve()
    prefix = args.protobuf_prefix.resolve()
    fragments = [connector_fragment(APP, "sdrConfig_.fuelTypes", 'LOGI("SDR sensor:'),
                 connector_fragment(BRIDGE, "config_.fuel_types", "// Location characterization")]
    includes = '\n'.join(sorted({line for path in (APP, BRIDGE)
        for line in path.read_text().splitlines()
        if line == '#include "openautolink/ev_connector_mapping.h"'}))
    harness = r'''
#include <aap_protobuf/service/sensorsource/SensorSourceService.pb.h>
#include <climits>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
INCLUDES
namespace FT = aap_protobuf::service::sensorsource::message;
using SensorSource = aap_protobuf::service::sensorsource::SensorSourceService;
std::string appDiagnostic;
void testLog(const char*, const char* line) { appDiagnostic = line; }
#define LOGI(...) testLog(__VA_ARGS__)
SensorSource app(const std::vector<int>& raw) {
    struct { std::vector<int> evConnectorTypes; } sdrConfig_{raw};
    SensorSource sensor;
    auto* ss = &sensor;
APP_FRAGMENT
    return sensor;
}
SensorSource bridge(const std::vector<int>& raw) {
    struct { std::vector<int> ev_connector_types; } config_{raw};
    SensorSource sensor;
    auto* ss = &sensor;
BRIDGE_FRAGMENT
    return sensor;
}
int main() {
    struct Case { std::vector<int> raw, expected; };
    const std::vector<Case> cases = {
        {{1,5}, {1,4}}, {{}, {1,4}},
        {{0}, {0}}, {{1}, {1}}, {{2}, {2}}, {{3}, {0}}, {{4}, {3}},
        {{5}, {4}}, {{6}, {5}}, {{7}, {6}}, {{8}, {7}}, {{9}, {8}},
        {{10}, {9}}, {{11}, {0}}, {{101}, {101}},
        {{-1, INT_MIN, INT_MAX, 12, 14, 100, 102, 65536}, {0,0,0,0,0,0,0,0}},
        {{3,11,14}, {0,0,0}}, {{5,5,1,6,0,101}, {4,4,1,5,0,101}},
        {{1,2,3,4,5,6,7,8,9,10,11,101}, {1,2,0,3,4,5,6,7,8,9,0,101}}
    };
    int failures = 0, checks = 0;
    for (const auto& path : {std::string("app"), std::string("bridge")}) {
        for (const auto& c : cases) {
            const auto original = c.raw;
            std::ostringstream bridgeDiagnostic;
            auto* previous = std::cerr.rdbuf(bridgeDiagnostic.rdbuf());
            appDiagnostic.clear();
            const auto sensor = path == "app" ? app(c.raw) : bridge(c.raw);
            std::cerr.rdbuf(previous);
            // Round-trip the actual protobuf; also rejects accidental invalid enums.
            std::string bytes;
            SensorSource decoded;
            if (!sensor.SerializeToString(&bytes) || !decoded.ParseFromString(bytes)) return 2;
            const auto& values = decoded.supported_ev_connector_types();
            const std::vector<int> actual(values.begin(), values.end());
            ++checks;
            std::ostringstream expectedDiagnostic;
            expectedDiagnostic << "OAL_EV_CONNECTORS_V1 source="
                << (c.raw.empty() ? "absent-fallback" : "vhal") << " raw=[";
            for (size_t i = 0; i < c.raw.size(); ++i) {
                if (i) expectedDiagnostic << ',';
                expectedDiagnostic << c.raw[i];
            }
            expectedDiagnostic << "] aa=[";
            for (size_t i = 0; i < c.expected.size(); ++i) {
                if (i) expectedDiagnostic << ',';
                expectedDiagnostic << c.expected[i];
            }
            expectedDiagnostic << ']';
            const auto diagnostic = path == "app" ? appDiagnostic : bridgeDiagnostic.str();
            ++checks;
            if (diagnostic.find(expectedDiagnostic.str()) == std::string::npos) {
                ++failures;
                std::cerr << "FAIL " << path << " missing diagnostic: "
                          << expectedDiagnostic.str() << '\n';
            }
            if (actual != c.expected || c.raw != original) {
                ++failures;
                std::cerr << "FAIL " << path << " raw=[";
                for (auto n : c.raw) std::cerr << n << ',';
                std::cerr << "] expected=[";
                for (auto n : c.expected) std::cerr << n << ',';
                std::cerr << "] actual=[";
                for (auto n : actual) std::cerr << n << ',';
                std::cerr << "]\n";
            }
        }
    }
    std::cout << "connector insertion checks=" << checks << " failures=" << failures << '\n';
    return failures ? 1 : 0;
}
'''
    harness = harness.replace('INCLUDES', includes).replace('APP_FRAGMENT', fragments[0]).replace('BRIDGE_FRAGMENT', fragments[1])
    for cmake in (APP.parent / 'CMakeLists.txt', BRIDGE.parent.parent / 'CMakeLists.txt'):
        paths = re.findall(r'"\$\{CMAKE_CURRENT_SOURCE_DIR\}(/[^"\n]*native/include)"', cmake.read_text())
        if not any((cmake.parent / p.lstrip('/')).resolve() == ROOT / 'native/include' for p in paths):
            print('FAIL canonical header not wired into ' + str(cmake), flush=True)
            return 1
    print('PASS app and bridge CMake canonical include paths', flush=True)
    with tempfile.TemporaryDirectory(prefix="oal-ev-connectors-") as tmp:
        # Exercise the real WSL staging prefix: a shared header outside bridge/
        # must survive the production build script's selective source copy.
        staging = (ROOT / 'scripts/build-bridge-wsl.sh').read_text().split(
            '# aasdk and openauto', 1)[0]
        staging_env = os.environ.copy()
        staging_env.update(HOME=tmp, REPO_ROOT=str(ROOT))
        subprocess.run(['bash', '-c', staging], env=staging_env, check=True)
        staged_header = Path(tmp) / 'openautolink-src/native/include/openautolink/ev_connector_mapping.h'
        canonical_header = ROOT / 'native/include/openautolink/ev_connector_mapping.h'
        if not staged_header.is_file() or staged_header.read_bytes() != canonical_header.read_bytes():
            print('FAIL bridge WSL staging omitted canonical EV connector header', flush=True)
            return 1
        print('PASS bridge WSL staging retains canonical EV connector header', flush=True)
        source = Path(tmp) / 'connector_insertion.cpp'
        exe = Path(tmp) / 'connector_insertion_test'
        source.write_text(harness)
        # Release protobuf setters omit debug enum assertions; our explicit checks
        # still verify unknown handling after serialization (never C assert()).
        command = ['g++', '-std=c++20', *([] if args.debug_protobuf else ['-DNDEBUG']),
                   '-O0', '-g0', '-Wall', '-Wextra', '-Werror',
                   '-Wno-deprecated-declarations', '-I', str(ROOT / 'native/include'),
                   '-I', str(proto / 'generated'), '-I', str(prefix / 'include'),
                   str(source), str(proto / 'liblab-proto.a'),
                   '-L', str(prefix / 'lib/x86_64-linux-gnu'), '-lprotobuf', '-pthread',
                   '-o', str(exe)]
        print('COMPILE:', ' '.join(command), flush=True)
        subprocess.run(command, check=True)
        if args.artifact_dir:
            args.artifact_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, args.artifact_dir / source.name)
            shutil.copy2(exe, args.artifact_dir / exe.name)
        env = os.environ.copy()
        env['LD_LIBRARY_PATH'] = str(prefix / 'lib/x86_64-linux-gnu') + ':' + env.get('LD_LIBRARY_PATH', '')
        result = subprocess.run([str(exe)], env=env)
        print('TEST_EXIT=' + str(result.returncode), flush=True)
        return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
