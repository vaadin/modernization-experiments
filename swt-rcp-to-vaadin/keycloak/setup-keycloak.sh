#!/usr/bin/env bash
#
# One-shot Keycloak setup for the multi-user RSSOwl→Vaadin PoC.
#
# Run this ON the Keycloak host (haneman) so it can talk to http://localhost:8080,
# which Keycloak exempts from its "HTTPS required" rule. For a bare install, kcadm.sh
# lives in $KEYCLOAK_HOME/bin. Point KCADM at it (or put it on PATH).
#
# It is idempotent-ish: "already exists" errors are ignored so you can re-run it.
#
# Usage:
#   export KC_ADMIN=admin
#   export KC_ADMIN_PW='your-admin-password'
#   # optional overrides:
#   #   KC_SERVER (default http://localhost:8080)
#   #   APP_BASE  (default http://localhost:8080  -- our Vaadin app's base URL)
#   #   KCADM     (default: kcadm.sh on PATH, else $KEYCLOAK_HOME/bin/kcadm.sh)
#   ./setup-keycloak.sh
#
set -euo pipefail

KC_SERVER="${KC_SERVER:-http://localhost:8080}"
APP_BASE="${APP_BASE:-http://localhost:8080}"
REALM="rssowl-realm"
CLIENT_ID="rssowl-app"
: "${KC_ADMIN:?set KC_ADMIN (Keycloak admin username)}"
: "${KC_ADMIN_PW:?set KC_ADMIN_PW (Keycloak admin password)}"

# Locate kcadm.sh
KCADM="${KCADM:-$(command -v kcadm.sh || true)}"
if [[ -z "${KCADM}" && -n "${KEYCLOAK_HOME:-}" ]]; then KCADM="${KEYCLOAK_HOME}/bin/kcadm.sh"; fi
[[ -x "${KCADM}" ]] || { echo "ERROR: kcadm.sh not found. Set KCADM=/path/to/kcadm.sh"; exit 1; }
echo "Using kcadm: ${KCADM}"

ignore_exists() { "$@" 2>/tmp/kc_err || grep -qiE "already exists|409|Conflict" /tmp/kc_err || { cat /tmp/kc_err; exit 1; }; }

echo "==> Authenticating to ${KC_SERVER} as ${KC_ADMIN}"
"${KCADM}" config credentials --server "${KC_SERVER}" --realm master \
  --user "${KC_ADMIN}" --password "${KC_ADMIN_PW}"

echo "==> Relaxing sslRequired on master (so http admin works) and creating realm '${REALM}'"
"${KCADM}" update realms/master -s sslRequired=NONE
ignore_exists "${KCADM}" create realms -s realm="${REALM}" -s enabled=true -s sslRequired=NONE
"${KCADM}" update "realms/${REALM}" -s sslRequired=NONE

echo "==> Creating confidential client '${CLIENT_ID}'"
ignore_exists "${KCADM}" create clients -r "${REALM}" \
  -s clientId="${CLIENT_ID}" \
  -s enabled=true \
  -s publicClient=false \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false \
  -s "redirectUris=[\"${APP_BASE}/login/oauth2/code/keycloak\",\"http://localhost:8088/login/oauth2/code/keycloak\"]" \
  -s "webOrigins=[\"${APP_BASE}\"]" \
  -s "attributes={\"post.logout.redirect.uris\":\"${APP_BASE}/*\"}"

CID="$("${KCADM}" get clients -r "${REALM}" -q clientId="${CLIENT_ID}" --fields id --format csv --noquotes | tr -d '\r')"
SECRET="$("${KCADM}" get "clients/${CID}/client-secret" -r "${REALM}" --fields value --format csv --noquotes | tr -d '\r')"

echo "==> Creating test users alice / bob (password = username)"
for u in alice bob; do
  ignore_exists "${KCADM}" create users -r "${REALM}" \
    -s username="${u}" -s enabled=true -s emailVerified=true \
    -s email="${u}@example.com" -s firstName="${u^}" -s lastName="Example"
  "${KCADM}" set-password -r "${REALM}" --username "${u}" --new-password "${u}" --temporary=false
done

cat <<EOF

======================================================================
 Keycloak '${REALM}' is ready.

 Give these to the app (the secret goes in an env var, NOT in git):

   client-id      : ${CLIENT_ID}
   client-secret  : ${SECRET}

 Test users: alice / alice   and   bob / bob
======================================================================
EOF
