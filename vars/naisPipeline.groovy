import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()
    def version
    def githubRepoName
    def uploadToNais = ['fpsak', 'fpfordel', 'fplos', 'fpabonnent', 'fpinfo', 'fpoppdrag', 'fptilbake', 'fprisk']
    def GIT_COMMIT_HASH_FULL
    
    pipeline {
        //agent any
        agent { label 'MASTER' }
        parameters {
            string(defaultValue: 't4', description: '', name: 'miljo')
        }
        options {
            timestamps()
        }
        environment {
            DOCKERREGISTRY = "repo.adeo.no:5443"
            ARTIFACTID = readMavenPom().getArtifactId()
        }
        stages {

            stage('Checkout scm') {
                steps {
                    script {
                        MILJO = params.miljo
                        Date date = new Date()
                        dockerRegistryIapp = "repo.adeo.no:5443"

                        checkout scm
                        gitCommitHasdh = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        GIT_COMMIT_HASH_FULL = sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        changelist = "_" + date.format("YYYYMMddHHmmss") + "_" + gitCommitHasdh
                        mRevision = maven.revision()
                        version = mRevision + changelist
                        githubRepoName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                        currentBuild.displayName = version

                        echo "Building $version"
                        echo "defaultMiljo $defaultMiljo"

                        fpgithub.updateBuildStatus(githubRepoName, "pending", GIT_COMMIT_HASH_FULL)

                    }
                }
            }

            stage('Set version') {
                steps {
                    sh "echo $version > VERSION"
                }
            }

            stage('Build') {
                steps {
                    script {
                        def additionalMavenArgs = ""
                        withMaven(mavenSettingsConfig: 'navMavenSettings') {
                            buildEnvironment = new buildEnvironment()
                            buildEnvironment.setEnv()
                            if (maven.javaVersion() != null) {
                                buildEnvironment.overrideJDK(maven.javaVersion())
                            }
                            sh "mvn --version"
                            envs = sh(returnStdout: true, script: 'env | sort -h').trim()
                            echo("envs: " + envs)
                            echo("artifact: " + ARTIFACTID)

                            mavenCommand = "mvn -B -Dfile.encoding=UTF-8 -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version -Djava.security.egd=file:///dev/urandom -DtrimStackTrace=false clean install"
                            if (ARTIFACTID.equalsIgnoreCase("vtp")) {
                                echo("MVN deploy for vtp")
                                mavenCommand = mavenCommand + " deploy"
                            }

                            if (ARTIFACTID == 'fpsak') {
                              String branchNavn = org.apache.commons.lang.RandomStringUtils.random(5, true, true)
                              String schemaNavnFPSAK = "fpsak_unit_" + branchNavn
                              String schemaNavnFPSAK_HIST = "fpsak_hist_unit_" + branchNavn
                              mavenCommand +=  " -Dflyway.placeholders.vl_fpsak_hist_schema_unit=$schemaNavnFPSAK_HIST -Dflyway.placeholders.vl_fpsak_hist_schema_unit=$schemaNavnFPSAK_HIST "
                            }
                            if (ARTIFACTID.equalsIgnoreCase("fpmock2")) {
                                echo("MVN deploy for fpmock2")
                                mavenCommand += " deploy"
                            }
                            if (ARTIFACTID == 'fpinfo') {
                                String rnd = org.apache.commons.lang.RandomStringUtils.random(5, true, true)
                                additionalMavenArgs = " -Dflyway.placeholders.fpinfo.fpsak.schema.navn=fpsak_$rnd -Dflyway.placeholders.fpinfoschema.schema.navn=fpinfoschema_$rnd -Dflyway.placeholders.fpinfo.schema.navn=fpinfo_$rnd "
                                mavenCommand += additionalMavenArgs
                                sh "mvn versions:use-latest-releases -Dincludes=no.nav.foreldrepenger:migreringer -DprocessDependencies=false"
                            }

                            sh "${mavenCommand}"
                            sh "docker build --pull -t $dockerRegistryIapp/$ARTIFACTID:$version ."
                            withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                              credentialsId   : 'nexusUser',
                                              usernameVariable: 'NEXUS_USERNAME',
                                              passwordVariable: 'NEXUS_PASSWORD']]) {
                                sh "docker login -u ${env.NEXUS_USERNAME} -p ${env.NEXUS_PASSWORD} ${dockerRegistryIapp} && docker push ${dockerRegistryIapp}/${ARTIFACTID}:${version}"

                                if (uploadToNais.contains(ARTIFACTID.toLowerCase())) {
                                    sh "nais upload -u ${env.NEXUS_USERNAME} -p ${env.NEXUS_PASSWORD} -a ${ARTIFACTID} -v ${version}"
                                }

                                if (ARTIFACTID == 'fpsak') {
                                    echo "-------------Deploy migreringene og regellmodell til Nexus -------------"
                                    sh "mvn -B -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version -pl migreringer,:beregningsgrunnlag-regelmodell -DskipITs -DskipUTs -Dmaven.test.skip deploy -DdeployOnly"
                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        script {
                            if (ARTIFACTID != 'fpinfo' && ARTIFACTID != 'fpsak') {
                                fpgithub.updateBuildStatus(githubRepoName, "success", GIT_COMMIT_HASH_FULL)
                            }
                        }
                    }
                    failure {
                        script {
                            if (ARTIFACTID != 'fpinfo' && ARTIFACTID != 'fpsak') {
                                fpgithub.updateBuildStatus(githubRepoName, "failure", GIT_COMMIT_HASH_FULL)
                            }
                        }
                    }
                }
            }

            stage('Tag master') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        sh "git tag $version -m $version"
                        if (ARTIFACTID == 'fpsak' || ARTIFACTID == 'fpinfo') {
                            sshagent(['deployer']) {
                                sh "git push --tags"
                            }
                        } else {
                            sh "git push origin --tag"
                        }
                    }
                }
            }
            stage('Deploy') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        def k8exists = fileExists 'k8s'
                        if (k8exists) {
                            dir('k8s') {
                                String msgColor = "#077040"
                                slackInfo("Deploy av *" + ARTIFACTID + "*:" + version + " til *" + MILJO + '*')

                                def props = readProperties interpolate: true, file: "application.${MILJO}.variabler.properties"
                                def value = "s/RELEASE_VERSION/${version}/g"
                                props.each { k, v -> value = value + ";s%$k%$v%g" }
                                sh "k config use-context $props.CONTEXT_NAME"
                                sh "sed \'$value\' app.yaml | k apply -f -"

                                def naisNamespace
                                if (MILJO == "p") {
                                    naisNamespace = "default"
                                } else {
                                    naisNamespace = MILJO
                                }
                                def exitCode = sh returnStatus: true, script: "k rollout status -n${naisNamespace} deployment/${ARTIFACTID}"
                                echo "exit code is $exitCode"

                                if (exitCode == 0) {
                                    def veraPayload = "{\"environment\": \"${MILJO}\",\"application\": \"${ARTIFACTID}\",\"version\": \"${version}\",\"deployedBy\": \"Jenkins\"}"
                                    def response = httpRequest([
                                            url                   : "https://vera.adeo.no/api/v1/deploylog",
                                            consoleLogResponseBody: true,
                                            contentType           : "APPLICATION_JSON",
                                            httpMode              : "POST",
                                            requestBody           : veraPayload,
                                            ignoreSslErrors       : true
                                    ])
                                }
                                slackInfo(msgColor, "_Deploy av $ARTIFACTID:$version til $MILJO var suksessfult._")
                            }
                        } else if (ARTIFACTID == 'vtp'){
                            echo "$ARTIFACTID deployes ikke til miljøene" 
                        } else {
                            if (ARTIFACTID == 'fpinfo' && MILJO == "t4") {
                                MILJO = "q1"
                            }
                            echo "Jira deploy"
                            jira = new jira()
                            jira.deployNais(ARTIFACTID, version, MILJO)
                        }
                    }
                }
            }
            stage('Start autotest dispatcher') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        def buildEnvironment = new buildEnvironment()
                        def changes = buildEnvironment.makeCommitLogString(currentBuild.rawBuild.changeSets)
                        build job: 'Foreldrepenger/autotest-dispatcher', parameters: [
                                [$class: 'StringParameterValue', name:  'application', value: "${ARTIFACTID}"],
                                [$class: 'StringParameterValue', name:  'version', value: "${version}"],
                                [$class: 'StringParameterValue', name:  'changelog', value: "${changes}"]
                        ], wait: false
                    }
                }
            }
        }
    }
}

def slackError(String tilleggsinfo) {
    slackSend color: "danger", channel: "#foreldrepenger-ci",  message: "${env.JOB_NAME} [${env.BUILD_NUMBER}] feilet: ${env.BUILD_URL} ${tilleggsinfo}"
}

def slackInfo(String msg) {
    slackInfo("#595959", msg)
}

def slackInfo(String color, String msg) {
    slackSend color: color, channel: "#foreldrepenger-ci", message: msg
}
