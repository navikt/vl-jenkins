#!/bin/sh

SERVERKEYSTORE=serverkeystore.p12
CERT_PEM=cert.pem
KEY_PEM=key.pem
KEYSTORE_FILE=keystore.jks
KEYSTORE_PASS=changeit
TRUSTSTORE_FILE=truststore.jks
TRUSTSTORE_PASS=changeit

mkdir -p ~/.keystores
cd ~/.keystores

openssl req -x509 -newkey rsa:2048 -keyout ${KEY_PEM} -out ${CERT_PEM} -days 365 -nodes -subj '/CN=localhost'

# local-host SSL
rm ${KEYSTORE_FILE}
openssl pkcs12 -export -name localhost-ssl -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}
keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias localhost-ssl -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}
rm ${SERVERKEYSTORE}

# app-key (jwt uststeder bl.a. i mocken, vi bruker samme noekkel per naa):
openssl pkcs12 -export -name app-key -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}
keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias app-key -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}

# clean up
rm ${SERVERKEYSTORE}
rm ${KEY_PEM}

# truststore for SSL:
rm ${TRUSTSTORE_FILE}
keytool -import -trustcacerts -alias localhost-ssl -file ${CERT_PEM} -keystore ${TRUSTSTORE_FILE} -storepass ${TRUSTSTORE_PASS} -noprompt

# clean up
rm ${CERT_PEM}
