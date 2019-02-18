package no.nav.jenkins

def setEnv() {
    env.LANG = "nb_NO.UTF-8"
    env.JAVA_HOME = "${tool 'jdk-1.8'}"
    env.PATH = "${tool 'default-maven'}/bin:${env.PATH}"
    env.ORACLE_HOME = "/u01/app/oracle/product/11.2.0/xe"
    env.MAVEN_OPTS = "-Xms512m -Xmx1024m -XX:+TieredCompilation -XX:TieredStopAtLevel=1 "


}

def overrideJDK(String targetJDK) {
    //Override default JDK
    switch(targetJDK) {

        case "11": env.JAVA_HOME = "${tool 'java-11'}"
            break
        case "10": env.JAVA_HOME = "${tool 'java-10'}"
            break
        case "1.8": env.JAVA_HOME = "${tool 'jdk-1.8'}"
            break
    }
}
