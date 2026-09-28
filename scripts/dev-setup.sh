#!/usr/bin/env bash
# UniPay — one-shot developer environment setup.
#
# Installs a JDK 21 + Maven toolchain if missing, then builds and tests the project.
# Tested on Debian/Ubuntu. On Windows use the bundled mvnw scripts with any JDK 21.
set -euo pipefail

need_java() { ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q '"21'; }

if need_java; then
  echo "→ Installing OpenJDK 21…"
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless
fi

if ! command -v mvn >/dev/null 2>&1 && [ ! -x ./mvnw ]; then
  echo "→ Installing Maven…"
  sudo apt-get install -y -qq maven
fi

MVN="./mvnw"; [ -x ./mvnw ] || MVN="mvn"
echo "→ Building and testing UniPay…"
"$MVN" clean verify
echo "✅ Done. Run the app with:  $MVN spring-boot:run          (H2 demo)"
echo "                        or:  docker compose up -d && $MVN spring-boot:run -Dspring-boot.run.profiles=mysql"
