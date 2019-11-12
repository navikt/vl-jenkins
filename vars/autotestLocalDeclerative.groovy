import hudson.tasks.test.AbstractTestResultAction
import no.nav.jenkins.*
import com.cloudbees.groovy.cps.NonCPS

@NonCPS
def makeTestStatus(currentBuild, allureUrl) {
    String testStatus = ""
    testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
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

def call() {
    def selftestUrls = [fpsak: "/fpsak/internal/health/selftest", spberegning: "/spberegning/internal/selftest", fprisk: "/fprisk/internal/selftest"]
    def vtpVersjon = "latest"
    def autotestVersjon = "latest"
    def keystores = new keystores()
    def dockerLokal = new dockerLocal()

    def applikasjon = params.applikasjon
    def applikasjonVersjon = params.applikasjonVersjon
    def changelog = params.changelog
    def profil
    def rc = params.rc

    pipeline {
        agent { label 'VTPAUTOTEST' }
        parameters {
            string(defaultValue: '', description: 'Applikasjon SUT, f.eks fpsak', name: 'applikasjon')
            string(defaultValue: '', description: 'SUT Versjon', name: 'applikasjonVersjon')
            string(defaultValue: '', description: 'Overstyr profil - default er applikasjonsnavn', name: 'profil')
            string(defaultValue: '', description: 'Changelog fra upstream job', name: 'changelog')
            booleanParam(defaultValue: false, description: 'Clean av SUT databasen', name: 'clean')
            booleanParam(defaultValue: false, description: 'Releasekandidat?', name: 'rc')
        }
        options {
            timestamps()
        }
        environment {
            DOCKERREGISTRY = "repo.adeo.no:5443"
            ARTIFACTID = readMavenPom().getArtifactId()
            LANG = "nb_NO.UTF-8"
            MAVEN_OPTS = "-Xms1024m -Xmx2048m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"
        }
        stages {
            stage("Init") {
                steps {
                    script {

                        if (params.profil == "") {
                            echo "Ingen testprofil oppgitt, setter til default samme som applikasjonsnavn: " + params.applikasjon
                            profil = params.applikasjon
                        } else {
                            echo "Overstyrer default testprofil med: " + params.profil
                            profil = params.profil
                        }

                        vtpVersjon = sh(script: "git ls-remote --tags git@vtp.github.com:navikt/vtp.git | sort -t '/' -k 3 -V | tail -2 | head -1 | grep -o '[^\\/]*\$'", returnStdout: true)?.trim()
                        autotestVersjon = sh(script: "git rev-parse HEAD", returnStdout: true)?.trim()
                        println "Using VTP version '${vtpVersjon}'"

                        // Setter environment.properties
                        sh(script: "rm -f target/allure-results/environment.properties")
                        sh(script: "mkdir -p target/allure-results")
                        sh(script: "echo sut.version=$applikasjonVersjon >> target/allure-results/environment.properties")
                        sh(script: "echo vtp.version=$vtpVersjon >> target/allure-results/environment.properties")
                        sh(script: "echo autotest.version=$autotestVersjon >> target/allure-results/environment.properties")

                        def workspace = pwd()
                        def dsfile = workspace + "/resources/pipeline/" + applikasjon + "_datasource.list"

                        if (!fileExists(dsfile)) {
                            println("Mangler datasource-config for applikasjonen, forventer $dsfile")
                            currentBuild.result('FAILED')
                        }
                    }
                }
            }

            stage("Cleanup docker ps og images") {
                steps {
                    script {
                        sh(script: "docker stop \$(docker ps -a -q) || true")
                        sh(script: "docker rm \$(docker ps -a -q) || true")
                    }
                }
            }

            stage("Pull") {
                steps {
                    script {
                        sh(script: "docker pull $dockerRegistry/$applikasjon:$applikasjonVersjon")
                        sh(script: "docker pull $dockerRegistry/vtp:$vtpVersjon")
                    }
                }
            }

            stage("Clean db") {
                when {
                    expression { params.clean == true }
                }
                steps{
                    script {
                        println("Implementasjon for å renske databasen. IKKE IMPLEMENTERT.")
                    }
                }
            }

            stage("Setup keystores") {
                steps {
                    script {
                        keystores.generateKeystoreAndTruststore("vtp")
                    }
                }
            }

            stage("Start VTP") {
                steps {
                    script {
                        sh(script: "rm -f vpt.env")
                        sh(script: "echo JAVAX_NET_SSL_TRUSTSTORE=/root/.modig/truststore.jks >> vtp.env")
                        sh(script: "echo JAVAX_NET_SSL_TRUSTSTOREPASSWORD=changeit >> vtp.env")
                        sh(script: "echo NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234 >> vtp.env")
                        sh(script: "echo NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=/root/.modig/keystore.jks >> vtp.env")
                        sh(script: "echo ISSO_OAUTH2_ISSUER=https://vtp:8063/rest/isso/oauth2 >> vtp.env")
                        sh(script: "echo VTP_KAFKA_HOST=localhost:9093 >> vtp.env")

                        sh(script: "docker run -d --name vtp --env-file vtp.env -v $workspace/.modig:/root/.modig -p 8636:8636 -p 8063:8063 -p 8060:8060 -p 8001:8001 -p 9093:9093  ${DOCKERREGISTRY}/vtp:${vtpVersjon}")
                    }
                }
            }

            stage("Start SUT") {
                steps {
                    script {
                        sh(script: "rm -f sut.env")
                        sh(script: "echo EXTRA_CLASS_PATH=:vtp-lib/* >> sut.env")

                        def workspace = pwd()
                        def host_ip = sh(script: "host a01apvl00312.adeo.no | sed 's/.*.\\s//'", returnStdout: true).trim()
                        println "Host: " + host_ip
                        String sutToRun = applikasjonVersjon

                        sh(script: "docker run -d --name $applikasjon --add-host=host.docker.internal:$host_ip -v $workspace/.modig:/var/run/secrets/naisd.io/ --env-file sut.env  --env-file $workspace/resources/pipeline/autotest.list --env-file $workspace/resources/pipeline/${applikasjon}_datasource.list -p 8080:8080 -p 8000:8000 --link vtp:vtp $DOCKERREGISTRY/$applikasjon:$sutToRun")
                    }

                }
            }

            stage("Verifiserer VTP") {
                steps {
                    script {
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
                }
            }

            stage("Venter på SUT") {
                steps {
                    script {
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
                }
            }

            stage("Kjør test") {
                steps {
                    script {
                        try {
                            configFileProvider([configFile(fileId: 'navMavenSettings', variable: 'MAVEN_SETTINGS')]) {
                                println "Workspace = " + workspace

                                sh(script: "export JAVAX_NET_SSL_TRUSTSTORE=${workspace}/.modig/truststore.jks")
                                sh(script: "export JAVAX_NET_SSL_TRUSTSTOREPASSWORD=changeit")
                                sh(script: "export NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234")
                                sh(script: "export NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=${workspace}/.modig/keystore.jks")

                                sh(script: 'export AUTOTEST_ENV=pipeline && ' +
                                        "export NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=${workspace}/.modig/keystore.jks && " +
                                        'export NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234 && ' +
                                        ' mvn test -s $MAVEN_SETTINGS -P ' + profil + ' -DargLine="AUTOTEST_ENV=pipepipe"')
                            }

                        } catch (error) {
                            currentBuild.result = 'FAILURE'
                            //slackSend color: "danger", message: ":collision: Autotest med profil _${context}_ feilet. Se ${env.BUILD_URL} for hvilke tester som feiler."
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
                }
            }

            stage("Notify") {
                steps {
                    script {
                        echo "verdien av rc er: " + rc

                        //TODO: Fjern eksplisitt sjekk på FPSAK når SPBEREGNING også er rapporterbar
                        echo "currentBuild.result er: " + currentBuild.result
                        def allureUrl = "https://jenkins-familie.adeo.no/job/Foreldrepenger/job/autotest-${applikasjon}/${env.BUILD_NUMBER}/allure/"


                        def testStatus = makeTestStatus(currentBuild, allureUrl)
                        println("Skal skrive status til kanal: " + testStatus)


                        if (profil.equalsIgnoreCase("spberegning") || profil.equalsIgnoreCase('fpsak')) {
                            if (currentBuild.result == 'FAILURE' || currentBuild.result == 'ABORTED') {
                                if (rc == 'true' && applikasjon == 'fpsak') {
                                    infoSlack("#FF0000", "fp-go-no-go", "VTP Autotest feilet for release-kandidat (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus)
                                    println("RC: Logget feil til kanal")
                                }

                                if (applikasjon == 'spberegning' && rc == 'false') {
                                    infoSlack("#FF0000", "spberegning-alerts", "VTP Autotest feilet (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                    println("Master build: Logget feil til kanal for spberegning")
                                }

                                infoSlack("#FF0000", "vtp-autotest-resultat", "VTP Autotest feilet (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                println("Master build: Logget feil til kanal")

                            } else {
                                if (rc == 'true' && applikasjon == 'fpsak') {
                                    infoSlack("#00FF00", "fp-go-no-go", "VTP Autotest kjørt uten feil for release-kandidat (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus)
                                    println("RC: Logget suksess til kanal")
                                }

                                if (applikasjon == 'spberegning' && rc == 'false') {
                                    infoSlack("#00FF00", "spberegning-alerts", "VTP Autotest kjørt uten feil (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                    println("Master build: Logget suksess til kanal for spberegning")
                                }

                                infoSlack("#00FF00", "vtp-autotest-resultat", "VTP Autotest kjørt uten feil (" + applikasjon + " [" + applikasjonVersjon + "]). " + testStatus + "\nEndringer:\n" + changelog)
                                println("Master build: Logget suksess til kanal")
                            }
                        }
                        testResultAction = null
                    }
                }
            }
        }
        post {
            failure {
                script {
                    //TODO: Stack trace er ikke inkludert. Nødvendig?
                    println("Bygg feilet!")
                    infoSlack("#FF0000", "vtp-autotest-resultat", "Noe gikk feil - Autotest feilet uten testkjøring (" + applikasjon + " [" + applikasjonVersjon + "]) ")
                    currentBuild.result = 'FAILURE'
                }
            }
            always {
                script {
                    //TODO: Må endres når vi skal kjøre i parallell
                    sh(script: "docker logs $applikasjon > sut_log.txt")
                    sh(script: "docker logs vtp > vtp_log.txt")
                    archiveArtifacts "sut_log.txt"
                    archiveArtifacts "vtp_log.txt"
                }
            }
        }
    }
}

def infoSlack(String color, String channel, String msg){
    String channel_hardkodet = "vtp-test-test"
    //TODO: Må fjerne channel_hardkodet og erstatte argumentet med channel før bruk.
    slackSend(color: color, channel: channel_hardkodet, message: msg)
}
