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

    def selftestUrls = [fpsak: "/fpsak/internal/health/isReady", spberegning: "/spberegning/internal/selftest"]


    timestamps {


        properties([disableConcurrentBuilds(), parameters([
                string(defaultValue: '', description: 'Applikasjon SUT, f.eks fpsak', name: 'applikasjon'),
                string(defaultValue: '', description: 'SUT Versjon', name: 'applikasjonVersjon'),
                string(defaultValue: '', description: 'Overstyr profil - default er applikasjonsnavn', name: 'profil'),
                string(defaultValue: '', description: 'Changelog fra upstream job', name: 'changelog'),
                booleanParam(defaultValue: false, description: 'Clean av SUT databasen', name: 'clean'),
                string(defaultValue: '', description: 'Denne jobben skal ikke kjøres manuelt', name: 'runkey'),
                booleanParam(defaultValue: false, description: 'Releasekandidat?', name: 'rc')
        ])
        ])

        def console = new console()
        def dockerLokal = new dockerLocal()
        def keystores = new keystores()

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

            if (runkey != "gandalf") {
                println("Fikk verdi ${runkey}")
                println("Ugyldig forsøk på å bygge jobb")
                exit 1
            }

            try {
                env.LANG = "nb_NO.UTF-8"

                String nyTag = "autotest"
                String sutToRun = applikasjonVersjon
                String dockerRegistry = "repo.adeo.no:5443"
                def vtpVersjon = "latest"


                stage("Init") {
                    console.printStage("Init")
                    //environment.setEnv()
                    env.MAVEN_OPTS = "-Xms1024m -Xmx2048m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"
                    //Extra juice. Overskriver verdier fra setEnv.

                    step([$class: 'WsCleanup'])
                    checkout scm

                    vtpVersjon = sh(script: "git ls-remote --tags git@vtp.github.com:navikt/vtp.git | sort -t '/' -k 3 -V | tail -2 | head -1 | grep -o '[^\\/]*\$'", returnStdout: true)?.trim();

                    println "Using VTP version '${vtpVersjon}'"

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
                    sh 'docker rmi $(docker images -a -q) || true'
                }

                stage("Pull") {
                    sh "docker pull $dockerRegistry/$applikasjon:$params.applikasjonVersjon"
                    sh "docker pull $dockerRegistry/fpmock2:$vtpVersjon"
                }

                stage("Clean db") {
                    if (params.clean) {
                        println("Her skal det ryddes.. Når klart")
                        //sh "$workspace/resources/pipeline/" + params.applikasjon + "_datasource.list"
                        String path = "${workspace}/resources/pipeline/${params.applikasjon}_datasource.list"
                        println(path)
                        //TODO: Split til Hashmap med kodeverdier for brukernavn, DB_URL og passord for database. Lag input til flyway clean.
                        String dbConfig = readFile(path)
                        def configMap = dbConfig.split("\n").collectEntries { entry ->
                            def pair = entry.split("=")
                            [(pair.first()): pair.last()]
                        }

                        String flywayCleanString = "-user=${configMap.DEFAULTDS_USERNAME} -password=${configMap.DEFAULTDS_PASSWORD} -url=${configMap.DEFAULTDS_URL} clean"
                        println("Running clean command")
                        sh "flyway $flywayCleanString"
                        println("Clean command finished")

                    }
                }


                stage("Setup keystores"){
                    keystores.generateKeystoreAndTruststore("fpmock2")
                }


                stage("Start VTP") {
                    sh(script: "rm -f vpt.env")
                    sh(script: "echo JAVAX_NET_SSL_TRUSTSTORE=/root/.modig/truststore.jks >> vtp.env")
                    sh(script: "echo JAVAX_NET_SSL_TRUSTSTOREPASSWORD=changeit >> vtp.env")
                    sh(script: "echo NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234 >> vtp.env")
                    sh(script: "echo NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=/root/.modig/keystore.jks >> vtp.env")

                    sh "docker run -d --name fpmock2 --env-file vtp.env -v $workspace/.modig:/root/.modig -p 8636:8636 -p 8063:8063 -p 8060:8060 -p 8001:8001  ${dockerRegistry}/fpmock2:${vtpVersjon}"
                }

                stage("Start SUT") {
                    sh(script: "rm -f sut.env")
                    sh(script: "echo EXTRA_CLASS_PATH=:vtp-lib/* >> sut.env")

                    def workspace = pwd()
                    //def host_ip = sh(script:"ip route show 0.0.0.0/0 | grep -Eo 'via \\S+' | awk '{ print \$2 }'", returnStdout: true)
                    def host_ip = InetAddress.localHost.hostAddress
                    println host_ip

                    sh "docker run -d --name $applikasjon --add-host=host.docker.internal:${host_ip} -v $workspace/.modig:/var/run/secrets/naisd.io/ --env-file sut.env  --env-file $workspace/resources/pipeline/autotest.list --env-file $workspace/resources/pipeline/" + params.applikasjon + "_datasource.list -p 8080:8080 -p 8000:8000 --link fpmock2:fpmock2 "+dockerRegistry+"/$applikasjon:$sutToRun"
                }

                stage("Verifiserer VTP") {
                    int retryLimit = 20
                    int vtpRetry = 0
                    int vent = 5
                    while (!dockerLokal.sjekkLokalVtpStatus()) {
                        if (vtpRetry > retryLimit) {
                            throw new Exception("Retrylimit oversteget for verifisering av VTP")
                            break
                        }
                        println("VTP ikke klar, venter $vent sekunder...")
                        println("VTP retry $vtpRetry av $retryLimit")
                        vtpRetry++
                    }

                }

                stage("Venter på SUT") {
                    int retryLimit = 30
                    int sutRetry = 0
                    int vent = 5
                    while (!dockerLokal.sjekkLokalApplikasjonStatus(selftestUrls.get(applikasjon))) {
                        if (sutRetry > retryLimit) {
                            throw new Exception("Retrylimit oversteget for å starte SUT " + $applikasjon)
                            break
                        }
                        println("SUT $applikasjon ikke klar, venter $vent sekunder...")
                        println("SUT retry $sutRetry av $retryLimit")
                        sutRetry++
                    }
                }

                stage("Kjør test") {
                    try {
                        configFileProvider([configFile(fileId: 'navMavenSettings', variable: 'MAVEN_SETTINGS')]) {
                            def workspace = pwd()

                            sh 'export AUTOTEST_ENV=pipeline && ' +
                                    "export CUSTOM_KEYSTORE_PATH=$workspace/.modig" +
                                    '&& mvn test -s $MAVEN_SETTINGS -P ' + profil + ' ' + mavenArgs + ' -DargLine="AUTOTEST_ENV=pipeline" -DargLine="isso.oauth2.issuer=https://fpmock2:8063/rest/isso/oauth2"'
                        }

                    } catch (error) {
                        currentBuild.result = 'FAILURE'
                        //slackSend color: "danger", message: ":collision: Autotest med profil _${params.context}_ feilet. Se ${env.BUILD_URL} for hvilke tester som feiler."
                    } finally {
                        archiveArtifacts "autotest/target/**/*"
                        junit 'autotest/target/surefire-reports/*.xml'
                        allure([
                                includeProperties: true,
                                jdk              : '',
                                properties       : [
                                        [allureDownloadUrl: "http://maven.adeo.no/nexus/service/local/repositories/m2internal/content/no/testhub/allure/2.7.0/allure-2.7.0.zip"],
                                        [reportVersion: "2.7.0"]
                                ],
                                reportBuildPolicy: 'ALWAYS',
                                results          : [[path: 'autotest/target/allure-results']]
                        ])

                    }
                }

                stage("Save logs") {
                    //TODO: Må endres når vi skal kjøre i parallell
                    sh "docker logs $applikasjon > sut_log.txt"
                    sh "docker logs fpmock2 > fpmock2_log.txt"
                    archiveArtifacts "sut_log.txt"
                    archiveArtifacts "fpmock2_log.txt"

                }

                stage("Notify") {
                    echo "verdien av rc er: " + rc

                    //TODO: Fjern eksplisitt sjekk på FPSAK når SPBEREGNING også er rapporterbar
                    echo "currentBuild.result er: " + currentBuild.result
                    def allureUrl = "http://foreldrepengerporten.adeo.no/jenkins/job/vtp-autotest-${applikasjon}/${env.BUILD_NUMBER}/allure/"


                    def testStatus = makeTestStatus(currentBuild.rawBuild.getAction(AbstractTestResultAction.class), allureUrl)
                    println("Skal skrive status til kanal: " + testStatus)


                    if (profil.equalsIgnoreCase("spberegning") || profil.equalsIgnoreCase('fpsak')) {
                        if (currentBuild.result == 'FAILURE' || currentBuild.result == 'ABORTED') {
                            if (rc == 'true' && applikasjon == 'fpsak') {
                                slackSend(color: "#FF0000", channel: "fp-go-no-go", message: "VTP Autotest feilet for release-kandidat (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus)
                                println("RC: Logget feil til kanal")
                            }

                            if (applikasjon == 'spberegning' && rc == 'false') {
                                slackSend(color: "#FF0000", channel: "spberegning-alerts", message: "VTP Autotest feilet (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                println("Master build: Logget feil til kanal for spberegning")
                            }

                            slackSend(color: "#FF0000", channel: "vtp-autotest-resultat", message: "VTP Autotest feilet (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                            println("Master build: Logget feil til kanal")

                        } else {
                            if (rc == 'true' && applikasjon == 'fpsak') {
                                slackSend(color: "#00FF00", channel: "fp-go-no-go", message: "VTP Autotest kjørt uten feil for release-kandidat (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus)
                                println("RC: Logget suksess til kanal")
                            }

                            if (applikasjon == 'spberegning' && rc == 'false') {
                                slackSend(color: "#00FF00", channel: "spberegning-alerts", message: "VTP Autotest kjørt uten feil (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                println("Master build: Logget suksess til kanal for spberegning")
                            }

                            slackSend(color: "#00FF00", channel: "vtp-autotest-resultat", message: "VTP Autotest kjørt uten feil (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                            println("Master build: Logget suksess til kanal")
                        }
                    }
                    testResultAction = null
                }
            } catch (Exception e) {
                println("Bygg feilet: $e")
                println(e.getMessage())
                currentBuild.result = 'FAILURE'
            }
        }
    }

}
