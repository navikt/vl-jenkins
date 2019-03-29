#!/usr/bin/env bash

if [[ $# -ne 1 ]];
then
    >&2 echo "Error: No app token provided"

    >&2 echo "Usage: $0 APP_TOKEN"
    exit 1
fi

# name of the organization we want to generate token for
ACCOUNT_NAME="navikt"
APP_TOKEN="$1"

INSTALLATION_ID_RESPONSE=$(curl -s -H "Authorization: Bearer ${APP_TOKEN}" \
    -H "Accept: application/vnd.github.machine-man-preview+json" \
    https://api.github.com/app/installations)

INSTALLATION_ID=$(echo $INSTALLATION_ID_RESPONSE | jq '.[] | select(.account.login=="'${ACCOUNT_NAME}'")' | jq -r '.id')

if [ -z "$INSTALLATION_ID" ];
then
   >&2 echo "Unable to obtain installation ID"
   >&2 echo "$INSTALLATION_ID_RESPONSE"
   exit 1
fi

# authenticate as github app and get access token
INSTALLATION_TOKEN_RESPONSE=$(curl -s -X POST \
        -H "Authorization: Bearer ${APP_TOKEN}" \
        -H "Accept: application/vnd.github.machine-man-preview+json" \
        https://api.github.com/app/installations/$INSTALLATION_ID/access_tokens)

INSTALLATION_TOKEN=$(echo $INSTALLATION_TOKEN_RESPONSE | jq -r '.token')

if [ -z "$INSTALLATION_TOKEN" ];
then
   >&2 echo "Unable to obtain installation token"
   >&2 echo "$INSTALLATION_TOKEN_RESPONSE"
   exit 1
fi

echo $INSTALLATION_TOKEN
