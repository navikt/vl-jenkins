#!/usr/bin/env bash
#
# signs a JWT assertion for the given github app using the
# provided private key. the expiration time is set to the maximum
# allowed value, i.e. 10 minutes in the future.
#
set -e

if [[ $# -ne 2 ]] || [[ ! -r "$1" ]];
then
    if [[ ! -r "$1" ]];
    then
        >&2 echo "Error: $1 is not readable"
    fi

    >&2 echo "Usage: $0 PRIVATE_KEY APP_ID"

    exit 1
fi

private_key_file="$1"
app_id=$2

current_time=$(date +%s)
# the maxiumum expiration time is 10 minutes, but we set it to 9 minutes
# to avoid clock skew differences between us and GitHub (which would cause GitHub to reject the token,
# because the expiration time is set too far in the future).
exp_time=$(($current_time + 9 * 60))

header='{
    "alg":"RS256"
}'
payload='{
    "iat":'$current_time',
    "exp":'$exp_time',
    "iss":'$app_id'
}'

compact_json() {
    jq -c '.' | tr -d '\n'
}

base64url_encode() {
    openssl enc -base64 -A | tr '+/' '-_' | tr -d '='
}

encoded_header=$(echo $header | compact_json | base64url_encode)
encoded_payload=$(echo $payload | compact_json | base64url_encode)
encoded_body="$encoded_header.$encoded_payload"
signature=$(echo -n $encoded_body | openssl dgst -binary -sha256 -sign "$private_key_file" | base64url_encode)

echo "$encoded_body.$signature"
