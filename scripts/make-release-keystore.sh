#!/usr/bin/env bash
# Creates a release signing keystore for the recorder app and the keystore.properties Gradle reads.
# The resulting files are gitignored. Keep the keystore somewhere safe (or, better, in an HSM):
# whoever holds it can ship an APK that the verifier's pinned signer fingerprint will accept.
set -euo pipefail
cd "$(dirname "$0")/.."
KS=release.jks
ALIAS=attestable-recorder
if [ -f "$KS" ]; then echo "$KS already exists; refusing to overwrite"; exit 1; fi
PASS=$(openssl rand -base64 24)
keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 3650 -storepass "$PASS" -keypass "$PASS" -dname "CN=Attestable Recorder, O=Attestable, C=US" >/dev/null
cat > keystore.properties <<PROPS
storeFile=$KS
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS
chmod 600 keystore.properties "$KS"
echo "Created $KS and keystore.properties (both gitignored)."
echo "Release signer SHA-256 (pin this with --signer):"
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$PASS" | awk '/SHA256:/{print $2}' | tr -d ':' | tr 'A-F' 'a-f'
