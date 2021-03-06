import hudson.tasks.test.AbstractTestResultAction
import no.nav.jenkins.*
import com.cloudbees.groovy.cps.NonCPS

@NonCPS
def makeTestStatus(currentBuild, allureUrl) {
    String testStatus = ""
    def testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
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
    def sutToRun = params.applikasjonVersjon
    def changelog = params.changelog
    def rc = params.rc
    def profil
    def doFailureStep = true

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
            DOCKERREGISTRY_ADEO = "repo.adeo.no:5443"
            DOCKERREGISTRY_GITHUB = "docker.pkg.github.com/navikt"
            ARTIFACTID = readMavenPom().getArtifactId()
            LANG = "nb_NO.UTF-8"
            MAVEN_OPTS = "-Xms1024m -Xmx2048m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"
        }
        stages {
            stage("Init") {
                steps {
                    script {
                        if (params.profil == "") {
                            echo "Ingen testprofil oppgitt, setter til default samme som applikasjonsnavn: " + applikasjon
                            profil = applikasjon
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

            stage("Setup keystores") {
                steps {
                    script {
                        keystores.generateKeystoreAndTruststore("vtp")
                    }
                }
            }

            stage("Start docker-compose avhengigheter") {
                steps {
                    script {
                        if (applikasjon.equalsIgnoreCase("fpsak")) {
                            withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                              credentialsId   : 'gpr_token',
                                              usernameVariable: 'GPR_USERNAME',
                                              passwordVariable: 'GPR_PASSWORD']]) {
                                sh(script: "docker login -u ${env.GPR_USERNAME} -p ${env.GPR_PASSWORD} ${DOCKERREGISTRY_GITHUB}")
                                def workspace = pwd()
                                sh(script:  "export FPABAKUS_IMAGE=${DOCKERREGISTRY_GITHUB}/fp-abakus/fpabakus &&" +
                                            "export VTP_IMAGE=${DOCKERREGISTRY_GITHUB}/vtp/vtp &&" +
                                            "export WORKSPACE=${workspace} &&" +
                                            "docker-compose -f ${workspace}/resources/pipeline/fpsak-docker-compose.yml pull -q &&" +
                                            "docker-compose -f ${workspace}/resources/pipeline/fpsak-docker-compose.yml up -d")
                            }
                        }
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

                        sh(script: "docker run -d --name $applikasjon --add-host=host.docker.internal:${host_ip} -v $workspace/.modig:/var/run/secrets/naisd.io/ --env-file sut.env  --env-file $workspace/resources/pipeline/autotest.list --env-file $workspace/resources/pipeline/${applikasjon}_datasource.list -p 8080:8080 -p 8000:8000  --network=\"pipeline_autotestverk\" $DOCKERREGISTRY_ADEO/$applikasjon-test:$sutToRun")
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
                            def workspace = pwd()
                            configFileProvider([configFile(fileId: 'navMavenSettings', variable: 'MAVEN_SETTINGS')]) {
                                println "Workspace = " + workspace

                                sh(script:  "export JAVAX_NET_SSL_TRUSTSTORE=${workspace}/.modig/truststore.jks")
                                sh(script:  "export JAVAX_NET_SSL_TRUSTSTOREPASSWORD=changeit")
                                sh(script:  "export NO_NAV_MODIG_SECURITY_APPCERT_PASSWORD=devillokeystore1234")
                                sh(script:  "export NO_NAV_MODIG_SECURITY_APPCERT_KEYSTORE=${workspace}/.modig/keystore.jks")

                                sh(script:  'export AUTOTEST_ENV=pipeline && ' +
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
                        //TODO: HARDCODET autotest-fpsak-new
                        echo "currentBuild.result er: " + currentBuild.result
                        def allureUrl = "https://jenkins-familie.adeo.no/job/Foreldrepenger/job/autotest-${applikasjon}-new/${env.BUILD_NUMBER}/allure/"


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
                            doFailureStep = false
                        }
                    }
                }
            }
        }
        post {
            failure {
                script {
                    if (doFailureStep) {
                        //TODO: Stack trace er ikke inkludert. Nødvendig?
                        println("Bygg feilet!")
                        infoSlack("#FF0000", "vtp-autotest-resultat", "Noe gikk feil - Autotest feilet uten testkjøring (" + applikasjon + " [" + applikasjonVersjon + "]) ")
                        currentBuild.result = 'FAILURE'
                    }
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
    //TODO: Må fjerne channel_hardkodet med channel før bruk. Sender til privat slack kanal.
    String channel_hardkodet = "vtp-test-test"
    slackSend(color: color, channel: channel_hardkodet, message: msg)
}
