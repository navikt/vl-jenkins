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

                            mavenCommand = "mvn -B -Dfile.encoding=UTF-8 -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version -Djava.security.egd=file:///dev/urandom -DtrimStackTrace=false" clean install"
                            if (ARTIFACTID.equalsIgnoreCase("fpmock2")) {
                                echo("MVN deploy for fpmock2")
                                mavenCommand = mavenCommand + " deploy"
                            }

                            sh "${mavenCommand}"
                            sh "docker build --pull -t $dockerRegistryIapp/$ARTIFACTID:$version ."
                            withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                                              credentialsId   : 'nexusUser',
                                              usernameVariable: 'NEXUS_USERNAME',
                                              passwordVariable: 'NEXUS_PASSWORD']]) {
                                sh "docker login -u ${env.NEXUS_USERNAME} -p ${env.NEXUS_PASSWORD} ${dockerRegistryIapp} && docker push ${dockerRegistryIapp}/${ARTIFACTID}:${version}"
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
                    sh "git tag $version -m $version"
                    sh "git push origin --tag"
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
                            }
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
