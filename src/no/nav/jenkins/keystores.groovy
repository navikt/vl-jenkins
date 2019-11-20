package no.nav.jenkins

import groovy.io.FileType



def generateKeystoreAndTruststore(String cnName){
    def SERVERKEYSTORE = "serverkeystore.p12"
    def CERT_PEM = "cert.pem"
    def KEY_PEM = "key.pem"
    def KEYSTORE_FILE = "keystore.jks"
    def KEYSTORE_PASS = "devillokeystore1234"
    def TRUSTSTORE_FILE = "truststore.jks"
    def TRUSTSTORE_PASS = "changeit"
    def KEYSTORE_FOLDER = ".modig"
    def CERTIFICATE_FOLDER =  "~/certificates"

    def CSR_DETAILS = """
[ req ]
default_bits = 2048
prompt = no
default_md = sha256
x509_extensions = v3_req
distinguished_name = dn

[ dn ]
C=NO
ST=Oslo
L=Oslo
O=Test Test
OU=Testing Domain
emailAddress=test@vtp.com
CN = ${cnName}

[ v3_req ]
subjectAltName = @alt_names
keyUsage = keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth
[ alt_names ]
DNS.1 = vtp
DNS.2 = localhost
DNS.3 = abakus
DNS.4 = fpabakus
"""

    // local-host SSL
    sh(script: "rm -rf ${KEYSTORE_FOLDER}")
    sh(script: "mkdir ${KEYSTORE_FOLDER}")
    sh(script: "rm -f csr_config.txt || true", returnStdout: true)
    sh(script: "echo \"${CSR_DETAILS}\" >> csr_config.txt ")
    sh(script: "openssl req -x509 -newkey rsa:2048 -keyout ${KEY_PEM} -out ${CERT_PEM} -days 365 -nodes -config csr_config.txt", returnStdout: true)
    sh(script: "openssl pkcs12 -export -name localhost-ssl -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias localhost-ssl -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "rm ${SERVERKEYSTORE}")


    // app-key (jwt uststeder bl.a. i mocken, vi bruker samme noekkel per naa):
    sh(script: "openssl pkcs12 -export -name app-key -in ${CERT_PEM} -inkey ${KEY_PEM} -out ${SERVERKEYSTORE} -password pass:${KEYSTORE_PASS}", returnStdout:true)
    sh(script: "keytool -importkeystore -destkeystore ${KEYSTORE_FILE} -srckeystore ${SERVERKEYSTORE} -srcstoretype pkcs12 -alias app-key -storepass ${KEYSTORE_PASS} -keypass ${KEYSTORE_PASS} -srcstorepass ${KEYSTORE_PASS}", returnStdout:true)

    // truststore for SSL:
    sh(script: "keytool -import -trustcacerts -alias localhost-ssl -file ${CERT_PEM} -keystore ${TRUSTSTORE_FILE} -storepass ${TRUSTSTORE_PASS} -noprompt", returnStdout:true)

    //Kopierer token til lokal mappe
    sh(script: "rm -rf certs")
    sh(script: "mkdir certs")
    sh(script: "cp ${CERTIFICATE_FOLDER}/* certs/")
    sh(script: "ls certs/", returnStdout: true)
 //   def dir = new File(CERTIFICATE_FOLDER)
 //   dir.eachFileRecurse (FileType.FILES) { file ->
 //       sh(script: "echo ${file}")
 //   }

    // Clean-up temporary files and move keystore and truststore
    sh(script: "rm ${CERT_PEM} ${KEY_PEM} ${SERVERKEYSTORE}")
    sh(script: "mv -t ${KEYSTORE_FOLDER} ${TRUSTSTORE_FILE} ${KEYSTORE_FILE}")


}
