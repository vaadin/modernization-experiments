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
#
# Requires a JDK 21+ on PATH (or JAVA_HOME pointing at one).
#
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env.local ]]; then
  set -a; . ./.env.local; set +a
else
  echo "WARNING: poc/headlines/.env.local not found — KEYCLOAK_CLIENT_SECRET unset; OIDC login will fail." >&2
fi

exec ./mvnw spring-boot:run
