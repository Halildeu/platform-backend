"""Run the actual compiled issuer/verifier with Maven's resolved runtime dependencies.

Prepare from repo root (Java 21):
  ./mvnw -pl meeting-service,transcript-service -am test-compile dependency:build-classpath \
    -DskipTests -DincludeScope=runtime -Dmdep.outputFile=target/recording-capability-classpath.txt
Then: python3 scripts/contracts/verify-recording-capability.py
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
classes = [root / module / "target/classes" for module in (
    "meeting-service", "transcript-service", "common-meeting-events")]
dependencies = root / "transcript-service/target/recording-capability-classpath.txt"
if not all(path.is_dir() for path in classes) or not dependencies.is_file():
    raise SystemExit("Missing compiled classes or runtime classpath; run the documented Maven preparation.")
classpath = os.pathsep.join([*(str(path) for path in classes), dependencies.read_text(encoding="utf-8").strip()])
java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
subprocess.run([str(java), "--class-path", classpath,
                str(root / "scripts/contracts/RecordingCapabilityRoundTrip.java")], check=True, cwd=root)
