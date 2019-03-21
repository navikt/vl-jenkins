import no.nav.jenkins.*

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()


    timestamps {
        dockerRegistryIapp = "repo.adeo.no:5443"
        def version
        maven = new maven()

        node('DOCKER') {
            Date date = new Date()
            stage('Checkout') {
                checkout scm
                GIT_COMMIT_HASH = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                changelist = "_" + date.format("YYYYMMDDHHmmss") + "_" + GIT_COMMIT_HASH
                mRevision = maven.revision()
                version = mRevision + changelist
                echo "Tag to be deployed $version"
            }

            stage('Build') {
                artifactId = maven.artifactId()
                buildEnvironment = new buildEnvironment()

                configFileProvider(
                        [configFile(fileId: 'navMavenSettings', variable: 'MAVEN_SETTINGS')]) {

                    if (maven.javaVersion() != null) {
                        buildEnvironment.overrideJDK(maven.javaVersion())
                    }

                    sh "mvn -U -B -s $MAVEN_SETTINGS -Dfile.encoding=UTF-8 -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version clean install"
                    sh "docker build --pull -t $dockerRegistryIapp/$artifactId:$version ."
                    withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                      credentialsId   : 'nexusUser',
                                      usernameVariable: 'NEXUS_USERNAME',
                                      passwordVariable: 'NEXUS_PASSWORD']]) {
                        sh "docker login -u ${env.NEXUS_USERNAME} -p ${env.NEXUS_PASSWORD} ${dockerRegistryIapp} && docker push ${dockerRegistryIapp}/${artifactId}:${version}"
                    }
                }

            }
        }
    }
}