import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()

    def GIT_COMMIT_HASH
    def GIT_COMMIT_HASH_FULL
    def MILJO = "t4"

    def latestTag
    def latestTagCommitHash
    def version
    def githubRepoName
    def artifactId
    boolean replay = false

    pipeline {
        agent any

        stages {

            stage('Checkout scm') {
                steps {
                    script {
                        Date date = new Date()
                        dockerRegistryIapp = "repo.adeo.no:5443"

                        checkout scm
                        GIT_COMMIT_HASH = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        GIT_COMMIT_HASH_FULL = sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        changelist = "_" + date.format("YYYYMMddHHmmss") + "_" + GIT_COMMIT_HASH
                        latestTag = sh(script: "git describe --tags", returnStdout: true)?.trim()
                        latestTagCommitHash = sh(script: "git describe --tags | sed 's/.*\\_//'", returnStdout: true)?.trim()
                        mRevision = maven.revision()
                        version = mRevision + changelist
                        githubRepoName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                        artifactId = maven.artifactId()

                        replay = GIT_COMMIT_HASH.equals(latestTagCommitHash)

                        currentBuild.displayName = version

                        if (replay) {
                            version = latestTag
                            echo "No change detected in sourcecode, skipping build and deploy existing tag={$latestTag}."
                        }

                        fpgithub.updateBuildStatus(githubRepoName, "pending", GIT_COMMIT_HASH_FULL)

                    }
                }
            }

            stage('Set version') {
                steps {
                    sh "mvn --version"
                    sh "echo $version > VERSION"
                }
            }

            stage('Build') {
                /*
                when {
                    expression { return !replay }
                }
                */
                steps {
                    script {
                        withMaven (mavenSettingsConfig: 'navMavenSettings') {
                            buildEnvironment = new buildEnvironment()
                            echo "Building $version"

                            if (maven.javaVersion() != null) {
                                buildEnvironment.overrideJDK(maven.javaVersion())
                            }

                            sh "mvn -B -Dfile.encoding=UTF-8 -DinstallAtEnd=true -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version clean install"
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

            stage('Tag master') {
                when {
                    branch 'master'
                    //expression { return !replay }
                }
                steps {
                    sh "git tag $version -m $version"
                    sh "git push origin --tag"
                }
            }
/*
            stage('Deploy') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        dir ('k8s') {
                            def props = readProperties  interpolate: true, file: "application.${MILJO}.variabler.properties"
                            def value = "s/RELEASE_VERSION/${version}/g"
                            props.each{ k,v -> value=value+";s%$k%$v%g" }
                            sh "k config use-context $props.CONTEXT_NAME"
                            sh "sed \'$value\' app.yaml > nais.yaml"
                            sh "k apply -f nais.yaml"
                            //sh "sed \'$value\' app.yaml | k apply -f -"

                            def naisNamespace
                            if (MILJO == "p") {
                                naisNamespace = "default"
                            } else {
                                naisNamespace = MILJO
                            }
                            def exitCode=sh returnStatus: true, script: "k rollout status -n${naisNamespace} deployment/${artifactId}"
                            echo "exit code is $exitCode"

                            if(exitCode == 0) {
                                def veraPayload = "{\"environment\": \"${MILJO}\",\"application\": \"${artifactId}\",\"version\": \"${version}\",\"deployedBy\": \"Jenkins\"}"
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
            */
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
}
