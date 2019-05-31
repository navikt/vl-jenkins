import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()
    def version
    def githubRepoName
    def GIT_COMMIT_HASH_FULL
    def artifactId
    def nodeLabel = (env.BRANCH_NAME == 'master') ? 'MASTER' : 'DOCKER'

    pipeline {
        //agent any
       agent { label nodeLabel }
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
                            buildEnvironment.setEnv()
                            if (maven.javaVersion() != null) {
                                buildEnvironment.overrideJDK(maven.javaVersion())
                            }

                            envs = sh(returnStdout: true, script: 'env | sort -h').trim()
                            echo("envs: " + envs)

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
                }
                steps {
                    sh "git tag $version -m $version"
                    sh "git push origin --tag"
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
}
