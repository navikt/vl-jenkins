import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()
    def version
    def githubRepoName
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
                        dockerRegistryGitHub = "docker.pkg.github.com/navikt"

                        checkout scm
                        gitCommitHasdh = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        GIT_COMMIT_HASH_FULL = sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        changelist = "_" + date.format("YYYYMMddHHmmss") + "_" + gitCommitHasdh
                        mRevision = maven.revision()
                        version = mRevision + changelist
                        githubRepoName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                        currentBuild.displayName = version

                        echo "Building $version"

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
                        //withMaven(mavenSettingsConfig: 'navMavenSettingsPkg') {
                        withMaven(mavenSettingsConfig: 'navMavenSettingsPkg', maven: 'default-maven') {
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
                                //echo("MVN deploy for vtp - DISABLED _ DISABLED _ DISABLED - fjernet p.g.a. feil - fixes ") //TODO: (OL): Fix
                                mavenCommand = mavenCommand + " deploy"
                            }

                            if (ARTIFACTID == 'fpsak') {
                                String branchNavn = org.apache.commons.lang.RandomStringUtils.random(5, true, true)
                                String schemaNavnFPSAK = "fpsak_unit_" + branchNavn
                                String schemaNavnFPSAK_HIST = "fpsak_hist_unit_" + branchNavn
                                mavenCommand += " -Dflyway.placeholders.vl_fpsak_hist_schema_unit=$schemaNavnFPSAK_HIST -Dflyway.placeholders.vl_fpsak_hist_schema_unit=$schemaNavnFPSAK_HIST "
                            }
                            if (ARTIFACTID == 'fpinfo') {
                                String rnd = org.apache.commons.lang.RandomStringUtils.random(5, true, true)
                                additionalMavenArgs = " -Dflyway.placeholders.fpinfo.fpsak.schema.navn=fpsak_$rnd -Dflyway.placeholders.fpinfoschema.schema.navn=fpinfoschema_$rnd -Dflyway.placeholders.fpinfo.schema.navn=fpinfo_$rnd "
                                mavenCommand += additionalMavenArgs
                                sh "mvn versions:use-latest-releases -Dincludes=no.nav.foreldrepenger:migreringer -DprocessDependencies=false"
                            }

                            if (ARTIFACTID != 'vtp') {
                                sh "${mavenCommand}"
                                sh "docker build --pull -t $dockerRegistryIapp/$ARTIFACTID:$version ."
                                withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                                  credentialsId   : 'nexusUser',
                                                  usernameVariable: 'NEXUS_USERNAME',
                                                  passwordVariable: 'NEXUS_PASSWORD']]) {
                                    sh "docker login -u ${env.NEXUS_USERNAME} -p ${env.NEXUS_PASSWORD} ${dockerRegistryIapp} && docker push ${dockerRegistryIapp}/${ARTIFACTID}:${version}"

                                    if (ARTIFACTID == 'fpsak') {
                                        echo "-------------Deploy migreringene og regellmodell til Nexus -------------"
                                        sh "mvn -B -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version -pl migreringer -DskipITs -DskipUTs -Dmaven.test.skip deploy -DdeployOnly"
                                    }
                                }
                            } else if (ARTIFACTID == 'vtp') {
                                sh "${mavenCommand}"
                                println("branch: ${ env.BRANCH_NAME }")
                                if ("master".equalsIgnoreCase("${ env.BRANCH_NAME }")) {
                                    sh "docker build --pull -t $dockerRegistryGitHub/$ARTIFACTID/$ARTIFACTID:$version ."
                                    withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                                      credentialsId   : 'gpr_token',
                                                      usernameVariable: 'GPR_USERNAME',
                                                      passwordVariable: 'GPR_PASSWORD']]) {
                                        sh "docker login -u ${env.GPR_USERNAME} -p ${env.GPR_PASSWORD} ${dockerRegistryGitHub} && docker push ${dockerRegistryGitHub}/$ARTIFACTID/$ARTIFACTID:$version && docker tag ${dockerRegistryGitHub}/$ARTIFACTID/$ARTIFACTID:$version ${dockerRegistryGitHub}/$ARTIFACTID/$ARTIFACTID:latest && docker push ${dockerRegistryGitHub}/$ARTIFACTID/$ARTIFACTID:latest"
                                    }

                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        script {
                            fpgithub.updateBuildStatus(githubRepoName, "success", GIT_COMMIT_HASH_FULL)
                        }
                    }
                    failure {
                        script {
                            fpgithub.updateBuildStatus(githubRepoName, "failure", GIT_COMMIT_HASH_FULL)
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
            stage('Start autotest dispatcher') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        def buildEnvironment = new buildEnvironment()
                        def changes = buildEnvironment.makeCommitLogString(currentBuild.rawBuild.changeSets)
                        build job: 'Foreldrepenger/autotest-dispatcher', parameters: [
                                [$class: 'StringParameterValue', name: 'application', value: "${ARTIFACTID}"],
                                [$class: 'StringParameterValue', name: 'version', value: "${version}"],
                                [$class: 'StringParameterValue', name: 'changelog', value: "${changes}"]
                        ], wait: false
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
                                slackInfo(msgColor, "_Deploy av $ARTIFACTID:$version til $MILJO var vellykket._")
                            }
                        } else if (ARTIFACTID == 'vtp') {
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
        }
    }
}

def slackError(String tilleggsinfo) {
    slackSend color: "danger", channel: "#foreldrepenger-ci", message: "${env.JOB_NAME} [${env.BUILD_NUMBER}] feilet: ${env.BUILD_URL} ${tilleggsinfo}"
}

def slackInfo(String msg) {
    slackInfo("#595959", msg)
}

def slackInfo(String color, String msg) {
    slackSend color: color, channel: "#foreldrepenger-ci", message: msg
}
