package no.nav.jenkins



def generateKeystoreAndTruststore(String cnName){
    def SERVERKEYSTORE = "serverkeystore.p12"
    def CERT_PEM = "cert.pem"
    def KEY_PEM = "key.pem"
    def KEYSTORE_FILE = "keystore.jks"
    def KEYSTORE_PASS = "devillokeystore1234"
    def TRUSTSTORE_FILE = "truststore.jks"
    def TRUSTSTORE_PASS = "changeit"
    def KEYSTORE_FOLDER = ".modig"

    def CSR_DETAILS = """
[req]
default_bits = 2048
prompt = no
default_md = sha256
req_extensions = req_ext
distinguished_name = dn

[ dn ]
C=NO
ST=Oslo
L=Oslo
O=Test TEst
OU=Testing Domain
emailAddress=test@vtp.com
CN = ${cnName}

[ req_ext ]
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = fpmock2
"""

    // local-host SSL
    sh(script: "rm -rf ${KEYSTORE_FOLDER}")
    sh(script: "mkdir ${KEYSTORE_FOLDER}")
    def file = new File("csr_config.txt")
    file.write(CSR_DETAILS)
    sh(script: "openssl req -x509 -newkey rsa:2048 -keyout ${KEY_PEM} -out ${CERT_PEM} -days 365 -nodes -config csr_config.txt", returnStdout: true)
    sh(script: "openssl pkcs12 -export -name localhost-ssl -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias localhost-ssl -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "rm ${SERVERKEYSTORE}")


    // app-key (jwt uststeder bl.a. i mocken, vi bruker samme noekkel per naa):
    sh(script: "openssl pkcs12 -export -name app-key -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias app-key -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)

    // truststore for SSL:
    sh(script: "keytool -import -trustcacerts -alias localhost-ssl -file ${CERT_PEM} -keystore ${TRUSTSTORE_FILE} -storepass ${TRUSTSTORE_PASS} -noprompt", returnStdout:true)

    // Clean-up temporary files and move keystore and truststore
    sh(script: "rm ${CERT_PEM} ${KEY_PEM} ${SERVERKEYSTORE}")
    sh(script: "mv -t ${KEYSTORE_FOLDER} ${TRUSTSTORE_FILE} ${KEYSTORE_FILE}")


}
