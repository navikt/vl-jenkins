package no.nav.jenkins

def properties() {
    return "-Dfile.encoding=UTF-8 -Djava.security.egd=file:///dev/urandom -DinstallAtEnd=true -DdeployAtEnd=true"
}


def artifactId() {
    pom = readMavenPom file: 'pom.xml'
    return "${pom.artifactId}"
}

def revision() {
    def matcher = readFile('pom.xml') =~ '<revision>(.+?)</revision>'
    return matcher ? matcher[0][1] : null
}
def javaVersion() {
    def matcher = readFile('pom.xml') =~ '<java.version>(.+?)</java.version>'
    return matcher ? matcher[0][1] : null
}
