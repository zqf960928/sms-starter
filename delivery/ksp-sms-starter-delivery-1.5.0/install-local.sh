#!/usr/bin/env bash
set -e

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Installing ksp-sms-spring-boot-starter to local Maven repository..."
mvn install:install-file \
  -Dfile="${BASE_DIR}/artifacts/ksp-sms-spring-boot-starter-1.5.0.jar" \
  -DpomFile="${BASE_DIR}/artifacts/pom.xml"

echo "Install completed successfully."



