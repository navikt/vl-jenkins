package no.nav.jenkins



def generateKeystoreAndTruststore(){
    def SERVERKEYSTORE = "serverkeystore.p12"
    def CERT_PEM = "cert.pem"
    def KEY_PEM = "key.pem"
    def KEYSTORE_FILE = "keystore.jks"
    def KEYSTORE_PASS = "devillokeystore1234"
    def TRUSTSTORE_FILE = "truststore.jks"
    def TRUSTSTORE_PASS = "changeit"
    def KEYSTORE_FOLDER = ".modig"

    // local-host SSL
    sh(script: "rm -rf ${KEYSTORE_FOLDER}")
    sh(script: "openssl req -x509 -newkey rsa:2048 -keyout ${KEY_PEM} -out ${CERT_PEM} -days 365 -nodes -subj '/CN=localhost'", returnStdout: true)
    sh(script: "openssl pkcs12 -export -name localhost-ssl -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias localhost-ssl -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "rm ${SERVERKEYSTORE}")


    // app-key (jwt uststeder bl.a. i mocken, vi bruker samme noekkel per naa):
    sh(script: "openssl pkcs12 -export -name app-key -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias app-key -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)

    // truststore for SSL:
    sh(script: "keytool -import -trustcacerts -alias localhost-ssl -file ${CERT_PEM} -keystore ${TRUSTSTORE_FILE} -storepass ${TRUSTSTORE_PASS} -noprompt", returnStdout:true)
    sh(script: "ls -la", returnStdout:true)


}
