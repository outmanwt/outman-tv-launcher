"""使用本机 JBR 与已有 Gradle 缓存离线编译并运行纯 Kotlin 核心测试。"""
from pathlib import Path
import json
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/core-tests"
CACHE = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
JAVA_HOME = Path(os.environ.get("TV_JAVA_HOME") or os.environ.get("JAVA_HOME") or "")
JAVA = JAVA_HOME / "bin" / ("java.exe" if os.name == "nt" else "java")


def dependency(group, artifact, version):
    directory = CACHE / group / artifact / version
    matches = sorted(directory.glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise FileNotFoundError(f"缺少已有缓存依赖：{directory}；本脚本不会下载依赖")
    return matches[0]


def execute(command, log_name):
    result = subprocess.run(
        [str(item) for item in command], cwd=ROOT, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, encoding="utf-8", errors="replace",
    )
    (OUTPUT / log_name).write_text(result.stdout, encoding="utf-8")
    print(result.stdout, end="", flush=True)
    return result


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not JAVA.is_file():
        raise FileNotFoundError(f"未找到现有 Java：{JAVA}；可设置 TV_JAVA_HOME")
    jars = [
        dependency("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.0"),
        dependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0"),
        dependency("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        dependency("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.0"),
        dependency("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        dependency("org.jetbrains", "annotations", "13.0"),
    ]
    sources = sorted((ROOT / "src/app/src/main/java/com/outman/tvlauncher/core").glob("*.kt"))
    suite = sys.argv[1] if len(sys.argv) > 1 else "CoreTest"
    if suite not in ("CoreTest", "RouteTest"):
        raise ValueError("测试套件必须为 CoreTest 或 RouteTest")
    tests = ROOT / f"script/tests/{suite}.kt"
    if not sources or not tests.is_file():
        raise FileNotFoundError("未找到核心 Kotlin 源码或测试套件")
    global OUTPUT
    OUTPUT = OUTPUT / suite
    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / "core-tests.jar"
    classpath = os.pathsep.join(str(path) for path in jars)
    compile_command = [
        JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp", classpath,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", os.pathsep.join(str(path) for path in (jars[1], jars[-1])),
        "-d", target, *sources, tests,
    ]
    print("正在使用已有 Kotlin 2.0.0 缓存编译核心测试", flush=True)
    compiled = execute(compile_command, "compile.log")
    report = {
        "java": str(JAVA), "dependencies": [str(path) for path in jars],
        "sources": [str(path) for path in [*sources, tests]],
        "compile_exit_code": compiled.returncode,
        "test_exit_code": None, "passed": 0, "failures": [], "all_passed": False,
    }
    exit_code = compiled.returncode
    if compiled.returncode == 0:
        tested = execute([
            JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp",
            os.pathsep.join(str(path) for path in (target, jars[1])), suite + "Kt",
        ], "test.log")
        report["test_exit_code"] = tested.returncode
        report["passed"] = len(re.findall(r"^通过：", tested.stdout, re.MULTILINE))
        report["failures"] = re.findall(r"失败：([^\r\n]+)", tested.stdout)
        summary = re.search(r"^全部 (\d+) 项(?:核心|线路)测试通过$", tested.stdout, re.MULTILINE)
        report["all_passed"] = (
            tested.returncode == 0 and summary is not None
            and int(summary.group(1)) == report["passed"]
        )
        exit_code = tested.returncode or (0 if report["all_passed"] else 1)
    (OUTPUT / "result.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print(f"实际通过 {report['passed']} 项；结果记录于 {OUTPUT / 'result.json'}", flush=True)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
