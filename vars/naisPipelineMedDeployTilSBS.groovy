import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()
    def version
    def githubRepoName
    def GIT_COMMIT_HASH_FULL
    def artifactId

    pipeline {
        agent any
        stages {
            stage('Checkout scm') {
                steps {
                    script {
                        Date date = new Date()
                        dockerRegistryIapp = "repo.adeo.no:5443"

                        checkout scm
                        gitCommitHasdh = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        GIT_COMMIT_HASH_FULL = sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        changelist = "_" + date.format("YYYYMMddHHmmss") + "_" + gitCommitHasdh
                        mRevision = maven.revision()
                        version = mRevision + changelist
                        githubRepoName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                        artifactId = maven.artifactId()
                        currentBuild.displayName = version

                        echo "Building $version"

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
                steps {
                    script {
                        withMaven (mavenSettingsConfig: 'navMavenSettings') {
                            buildEnvironment = new buildEnvironment()

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

                        fpgithub.updateBuildStatus(githubRepoName, "success", GIT_COMMIT_HASH_FULL)
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
            stage('Deploy master til preprod') {
                when {
                    branch 'master'
                }
                steps {
                    sh "familie-kubectl config use-context dev-sbs"
                    sh "sed \'s/RELEASE_VERSION/${version}/g\' app-preprod.yaml | familie-kubectl apply -f -"
                }
            }
            stage('Deploy master til prod?') {
                when {
                    beforeInput true
                    branch 'master'
                }
                options {
                    timeout(time: 3, unit: 'DAYS')
                }
                input {
                    message "Vil du deploye master til prod?"
                    ok "Ja, jeg vil deploye :)"
                }
                steps {
                    sh "familie-kubectl config use-context prod-sbs"
                    sh "sed \'s/RELEASE_VERSION/${version}/g\' app-prod.yaml | familie-kubectl apply -f -"
                }
            }
            stage('Deploy branch til preprod?') {
                when {
                    not {
                        branch 'master'
                    }
                }
                options {
                    timeout(time: 30, unit: 'MINUTES')
                }
                input {
                    message "Vil du deploye til preprod?"
                    ok "Ja, jeg vil deploye :)"
                }
                steps {
                    sh "familie-kubectl config use-context dev-sbs"
                    sh "sed \'s/RELEASE_VERSION/${version}/g\' app-preprod.yaml | familie-kubectl apply -f -"
                }
            }
        }

        post {
            failure {
                script {
                    fpgithub.updateBuildStatus(githubRepoName, "failure", GIT_COMMIT_HASH_FULL)
                }
            }
        }

    }
}
