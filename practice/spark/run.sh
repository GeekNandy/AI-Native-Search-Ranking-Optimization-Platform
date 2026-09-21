#!/usr/bin/env bash
set -euo pipefail

# This runner uses an installed Spark distribution and JDK; no Python program.
spark_prep_dir="${SPARK_PREP_HOME:-${SPARK_HOME:-}}"
if [[ -z "$spark_prep_dir" || ! -d "$spark_prep_dir/jars" ]]; then
  echo 'Set SPARK_PREP_HOME to your extracted Apache Spark 4.0.1 directory.' >&2
  exit 2
fi
spark_prep_packet="$(cd -- "$(dirname -- "$0")" && pwd)"
cd "$spark_prep_packet"
mkdir -p build/classes
if command -v javac >/dev/null 2>&1; then
  spark_prep_compiler=(javac)
else
  spark_prep_compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
if command -v jar >/dev/null 2>&1; then
  spark_prep_archiver=(jar)
else
  spark_prep_archiver=(java -m jdk.jartool/sun.tools.jar.Main)
fi
spark_prep_jars=("$spark_prep_dir"/jars/*.jar)
printf -v spark_prep_classpath '%s:' "${spark_prep_jars[@]}"
"${spark_prep_compiler[@]}" --release 17 -cp "${spark_prep_classpath%:}" \
  -d build/classes SparkInterviewPractice.java Lab01Ctr.java
"${spark_prep_archiver[@]}" --create --file build/spark-interview-practice.jar \
  -C build/classes .
SPARK_HOME="$spark_prep_dir" SPARK_LOCAL_IP=127.0.0.1 \
  "$spark_prep_dir/bin/spark-submit" --class SparkInterviewPractice \
  --master 'local[2]' build/spark-interview-practice.jar "$@"
