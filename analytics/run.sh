#!/usr/bin/env bash
set -euo pipefail
ranking_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
ranking_spark="${SPARK_HOME:?Set SPARK_HOME to an Apache Spark 4.0.1 distribution}"
[[ -d "$ranking_spark/jars" ]] || { echo 'SPARK_HOME must contain jars/' >&2; exit 2; }
mkdir -p "$ranking_root/analytics/build/classes"
ranking_compiler=(javac)
command -v javac >/dev/null || ranking_compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
ranking_archiver=(jar)
command -v jar >/dev/null || ranking_archiver=(java -m jdk.jartool/sun.tools.jar.Main)
ranking_jars=("$ranking_spark"/jars/*.jar)
printf -v ranking_classpath '%s:' "${ranking_jars[@]}"
"${ranking_compiler[@]}" --release 17 -cp "${ranking_classpath%:}" -d "$ranking_root/analytics/build/classes" \
  "$ranking_root/src/main/java/com/alpas/ainativesearchrankingoptimizationplatform/ml/ClickModel.java" \
  "$ranking_root/analytics/src/main/java/com/alpas/ranking/analytics/RankingPipeline.java"
"${ranking_archiver[@]}" --create --file "$ranking_root/analytics/build/ranking-analytics.jar" -C "$ranking_root/analytics/build/classes" .
exec "$ranking_spark/bin/spark-submit" --class com.alpas.ranking.analytics.RankingPipeline \
  --master "${SPARK_MASTER:-local[2]}" --driver-memory "${SPARK_DRIVER_MEMORY:-1g}" \
  "$ranking_root/analytics/build/ranking-analytics.jar" "$@"
