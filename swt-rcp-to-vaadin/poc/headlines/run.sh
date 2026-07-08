#!/usr/bin/env bash
#
# Launch the multi-user PoC. Reads secrets from .env.local (git-ignored) so they never
# touch the command line, shell history, or version control.
#
# One-time setup:
#   echo "export KEYCLOAK_CLIENT_SECRET='<the secret from setup-keycloak.sh>'" > poc/headlines/.env.local
#
# Then just: ./run.sh
#
set -euo pipefail
cd "$(dirname "$0")"

# arm64 JDK 21 (not the Java 26 sdkman default)
export JAVA_HOME="${JAVA_HOME:-/Users/ehaase/Library/Java/JavaVirtualMachines/openjdk-21.0.1/Contents/Home}"

if [[ -f .env.local ]]; then
  set -a; . ./.env.local; set +a
else
  echo "WARNING: poc/headlines/.env.local not found — KEYCLOAK_CLIENT_SECRET unset; OIDC login will fail." >&2
fi

exec ./mvnw -o spring-boot:run
