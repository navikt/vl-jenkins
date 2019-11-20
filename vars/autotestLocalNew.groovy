import hudson.tasks.test.AbstractTestResultAction
import no.nav.jenkins.*
import com.cloudbees.groovy.cps.NonCPS

@NonCPS
def makeTestStatus(testResultAction, allureUrl) {
    String testStatus = ""
    if (testResultAction != null) {
        def total = testResultAction.totalCount
        def failed = testResultAction.failCount
        def skipped = testResultAction.skipCount
        def failureDiff = testResultAction.failureDiffString
        def passed = total - failed - skipped
        testStatus = "\nTest Status:\n  OK: ${passed}, Feilet: ${failed} Diff: ${failureDiff}, Hoppet over: ${skipped}. Rapport: " + allureUrl
        print("Laget teststatus = " + testStatus)
    }
    return testStatus
}

def call(body) {
    def config = [:]
    body.delegate = config
    body()

    def selftestUrls = [fpsak: "/fpsak/internal/health/selftest", spberegning: "/spberegning/internal/selftest", fprisk: "/fprisk/internal/selftest"]


    timestamps {


        properties([disableConcurrentBuilds(), parameters([
                string(defaultValue: '', description: 'Applikasjon SUT, f.eks fpsak', name: 'applikasjon'),
                string(defaultValue: '', description: 'SUT Versjon', name: 'applikasjonVersjon'),
                string(defaultValue: '', description: 'Overstyr profil - default er applikasjonsnavn', name: 'profil'),
                string(defaultValue: '', description: 'Changelog fra upstream job', name: 'changelog'),
                booleanParam(defaultValue: false, description: 'Clean av SUT databasen', name: 'clean'),
                booleanParam(defaultValue: false, description: 'Releasekandidat?', name: 'rc')
        ])
        ])

        def console = new console()
        def dockerLokal = new dockerLocal()
        def keystores = new keystores()
        def nais = new nais()

        //Deaktivert p.g.a ex-base
        def mvnTestProperties = ["junit.jupiter.execution.parallel.enabled"                 : "true",
                                 "junit.jupiter.execution.parallel.config.strategy"         : "fixed",
                                 "junit.jupiter.execution.parallel.config.fixed.parallelism": "4"]

        def toMavenArgs = {
            it.collect { "-D${it.key}=${it.value}" } join " "
        }
        def mavenArgs = toMavenArgs(mvnTestProperties)


        def profil
        if (params.profil == "") {
            echo "Ingen testprofil oppgitt, setter til default samme som applikasjonsnavn: " + params.applikasjon
            profil = params.applikasjon
        } else {
            echo "Overstyrer default testprofil med: " + params.profil
            profil = params.profil
        }

        node('VTPAUTOTEST') {

            try {
                env.LANG = "nb_NO.UTF-8"

                String nyTag = "autotest"
                String sutToRun = applikasjonVersjon
                String dockerRegistry = "repo.adeo.no:5443"
                def vtpVersjon = "latest"
                def autotestVersjon = "latest"

                stage("Init") {
                    console.printStage("Init")
                    //environment.setEnv()
                    env.MAVEN_OPTS = "-Xms1024m -Xmx2048m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"
                    //Extra juice. Overskriver verdier fra setEnv.

                    step([$class: 'WsCleanup'])
                    checkout scm

                    vtpVersjon = sh(script: "git ls-remote --tags git@vtp.github.com:navikt/vtp.git | sort -t '/' -k 3 -V | tail -2 | head -1 | grep -o '[^\\/]*\$'", returnStdout: true)?.trim();



                    autotestVersjon = sh(script: "git rev-parse HEAD", returnStdout: true)?.trim();
                    println "Using VTP version '${vtpVersjon}'"

                    // Setter environment.properties
                    sh(script: "rm -f target/allure-results/environment.properties")
                    sh(script: "mkdir -p target/allure-results")
                    sh(script: "echo sut.version=$params.applikasjonVersjon >> target/allure-results/environment.properties")
                    sh(script: "echo vtp.version=$vtpVersjon >> target/allure-results/environment.properties")
                    sh(script: "echo autotest.version=$autotestVersjon >> target/allure-results/environment.properties")

                    def workspace = pwd()
                    def dsfile = workspace + "/resources/pipeline/" + applikasjon + "_datasource.list"

                    if (!fileExists(dsfile)) {
                        println("Mangler datasource-config for applikasjonen, forventer $dsfile")
                        currentBuild.result('FAILED')
                    }
                }

                stage("Cleanup docker ps og images") {
                    sh 'docker stop $(docker ps -a -q) || true'
                    sh 'docker rm $(docker ps -a -q) || true'
                }

                stage("Pull") {
                    sh "docker pull $dockerRegistry/$applikasjon:$params.applikasjonVersjon"
                    sh "docker pull $dockerRegistry/vtp:$vtpVersjon"
                }

                stage("Setup keystores") {
                    keystores.generateKeystoreAndTruststore("vtp")
                }

                //TODO: Gjør denne generisk
                stage("Start andre avhengigheter"){
                    if(applikasjon.equalsIgnoreCase("fpsak")) {
                        def workspace = pwd()
                        abakus_version = sh(script: "git ls-remote --tags git@fp-abakus.github.com:navikt/fp-abakus.git | grep -o '[^\\/]*\$' | sort -t '_' -k 1 -g | tail -n 2 | head -1", returnStdout: true)?.trim();

                        echo "abakusversjon = ${dockerRegistry}/fpabakus:$abakus_version"
                        sh "export ABAKUS_IMAGE=${dockerRegistry}/fpabakus:${abakus_version} &&" +
                        "export VTP_IMAGE=${dockerRegistry}/vtp:${vtpVersjon} &&" +
                        "export WORKSPACE=${workspace} &&" +
                        "docker-compose -f $workspace/resources/pipeline/fpsak-docker-compose.yml up -d"
                    }
                }

                stage("Start SUT") {
                    sh(script: "rm -f sut.env")
                    sh(script: "echo EXTRA_CLASS_PATH=:vtp-lib/* >> sut.env")

                    def workspace = pwd()
                    def host_ip = sh(script: "host a01apvl00312.adeo.no | sed 's/.*.\\s//'", returnStdout: true).trim()
                    println "Host: " + host_ip

                    //TODO: Gjør denne generisk
                    if(applikasjon.equalsIgnoreCase("fpsak")){
                        sh "docker run -d --name $applikasjon --add-host=host.docker.internal:${host_ip} -v $workspace/.modig:/var/run/secrets/naisd.io/ --env-file sut.env  --env-file $workspace/resources/pipeline/autotest.list --env-file $workspace/resources/pipeline/" + params.applikasjon + "_datasource.list -p 8080:8080 -p 8000:8000  --network=\"pipeline_autotestverk\" " + dockerRegistry + "/$applikasjon:$sutToRun"
                    } else {
                        sh "docker run -d --name $applikasjon --add-host=host.docker.internal:${host_ip} -v $workspace/.modig:/var/run/secrets/naisd.io/ --env-file sut.env  --env-file $workspace/resources/pipeline/autotest.list --env-file $workspace/resources/pipeline/" + params.applikasjon + "_datasource.list -p 8080:8080 -p 8000:8000 --link vtp:vtp " + dockerRegistry + "/$applikasjon:$sutToRun"
                    }
                }

                stage("Verifiserer VTP") {
                    int retryLimit = 20
                    int vtpRetry = 0
                    int vent = 5
                    while (!dockerLokal.sjekkLokalVtpStatus()) {
                        if (vtpRetry > retryLimit) {
                            throw new Exception("Retrylimit oversteget for verifisering av VTP")
                        }
                        println("VTP ikke klar, venter $vent sekunder...")
                        println("VTP retry $vtpRetry av $retryLimit")
                        vtpRetry++
                        sleep(vent)
                    }

                }

                stage("Venter på SUT") {
                    int retryLimit = 30
                    int sutRetry = 0
                    int vent = 5
                    while (!dockerLokal.sjekkLokalApplikasjonStatus(selftestUrls.get(applikasjon))) {
                        if (sutRetry > retryLimit) {
                            throw new Exception("Retrylimit oversteget for å starte SUT " + applikasjon)
                        }
                        println("SUT $applikasjon ikke klar, venter $vent sekunder...")
                        println("SUT retry $sutRetry av $retryLimit")
                        sutRetry++
                        sleep(vent)
                    }
                }

                stage("Kjør test") {

                    try {
                        configFileProvider([configFile(fileId: 'navMavenSettings', variable: 'MAVEN_SETTINGS')]) {
                            println "Workspace = " + workspace

                            sh(script: "export JAVAX_NET_SSL_TRUSTSTORE=${workspace}/.modig/truststore.jks")
                            sh(script: "export JAVAX_NET_SSL_TRUSTSTOREPASSWORD=changeit")
                            sh(script: "export NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234")
                            sh(script: "export NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=${workspace}/.modig/keystore.jks")

                            sh 'export AUTOTEST_ENV=pipeline && ' +
                                    "export NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=${workspace}/.modig/keystore.jks && " +
                                    'export NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234 && ' +
                                    ' mvn test -s $MAVEN_SETTINGS -P ' + profil + ' -DargLine="AUTOTEST_ENV=pipepipe"'
                        }

                    } catch (error) {
                        currentBuild.result = 'FAILURE'
                        //slackSend color: "danger", message: ":collision: Autotest med profil _${params.context}_ feilet. Se ${env.BUILD_URL} for hvilke tester som feiler."
                    } finally {
                        archiveArtifacts "target/**/*"
                        junit 'target/surefire-reports/*.xml'
                        allure([
                                includeProperties: true,
                                jdk              : '',
                                properties       : [
                                        [allureDownloadUrl: "http://maven.adeo.no/nexus/service/local/repositories/m2internal/content/no/testhub/allure/2.7.0/allure-2.7.0.zip"],
                                        [reportVersion: "2.7.0"]
                                ],
                                reportBuildPolicy: 'ALWAYS',
                                results          : [[path: 'target/allure-results']]
                        ])

                    }
                }

            } catch (Exception e) {
                println("Bygg feilet: $e")
                println(e.getMessage())
                currentBuild.result = 'FAILURE'
            } finally {
                //TODO: Må endres når vi skal kjøre i parallell
                sh "docker logs $applikasjon > sut_log.txt"
                sh "docker logs vtp > vtp_log.txt"
                archiveArtifacts "sut_log.txt"
                archiveArtifacts "vtp_log.txt"
            }
        }
    }

}
