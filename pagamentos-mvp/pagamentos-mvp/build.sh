#!/usr/bin/env sh
# Requer JDK 17+ (testado com 21). Sem Maven/Gradle e sem dependências externas.
set -e
rm -rf out && mkdir out
javac -Xlint:all -d out $(find src -name '*.java')
case "$1" in
  test) java -Dstdout.encoding=UTF-8 -cp out br.pay.Tests ;;
  run)  PAY_API_KEY="${PAY_API_KEY:-dev-key}" java -cp out br.pay.Api "${2:-8080}" ;;
  *)    echo "uso: ./build.sh test | ./build.sh run [porta]" ;;
esac
