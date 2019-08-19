package no.nav.jenkins

def setEnv() {
    env.LANG = "nb_NO.UTF-8"
    env.JAVA_HOME = "${tool '11'}"
    env.PATH = "${tool 'default-maven'}/bin:${env.PATH}:/opt/yarn-v1.12.3/bin:/usr/local/lib/node/nodejs/bin"
    env.ORACLE_HOME = "/u01/app/oracle/product/11.2.0/xe"
    env.MAVEN_OPTS = "-Xms512m -Xmx1024m -XX:+TieredCompilation -XX:TieredStopAtLevel=1 "


}

def overrideJDK(String targetJDK) {
    //Override default JDK
    switch(targetJDK) {

        case "11": env.JAVA_HOME = "${tool '11'}"
            break
        case "10": env.JAVA_HOME = "${tool '10'}"
            break
        case "1.8": env.JAVA_HOME = "${tool 'jdk-1.8'}"
            break
    }
}

def makeCommitLogString(def changeSet){

    StringBuilder sb = new StringBuilder()
    changeSet.each {
        def entries = it.items
        println("Entries: " + entries.toString())
        entries.each {
            def entry = it
            sb.append("- ${entry.commitId.substring(0,12)} av ${entry.author}: \"${entry.msg}\"\n")
        }
    }
    return sb.toString()
}