import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def fpgithub = new fpgithub()
    def version
    def GIT_COMMIT_HASH_FULL

    pipeline {
        agent any

        options {
            timestamps()
        }

        environment {
            LANG = "nb_NO.UTF-8"
            JAVA_HOME = "${tool 'java-11'}"
            PATH = "${tool 'maven-3.5.3'}/bin:${env.PATH}"
            ORACLE_HOME = "/u01/app/oracle/product/11.2.0/xe"
            ARTIFACTID = readMavenPom().getArtifactId()
        }

        stages {

            stage('Checkout scm') { // checkout only tags.
                steps {
                    script {
                        Date date = new Date()

                        checkout scm
                        gitCommitHash = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        GIT_COMMIT_HASH_FULL = sh(script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        changelist = "_" + date.format("YYYYMMddHHmmss") + "_" + gitCommitHash
                        mRevision = maven.revision()
                        version = mRevision + changelist

                        currentBuild.displayName = version

                        echo "Building $version"
                    }
                }
            }

            stage('Build branch') {
                when {
                    not {
                        anyOf {
                            branch "master"
                        }
                    }
                }
                steps {
                    script {

                        def mRevision = maven.revision()
                        def tagName = env.BRANCH_NAME.tokenize('/')[-1] + "-" + mRevision
                        currentBuild.displayName = tagName + "-SNAPSHOT"

                        withMaven(mavenSettingsConfig: 'navMavenSettings') {

                            buildEnvironment = new buildEnvironment()
                            if (maven.javaVersion() != null) {
                                buildEnvironment.overrideJDK(maven.javaVersion())
                            }

                            sh "mvn -B -Dfile.encoding=UTF-8 -Dsha1= -Dchangelist=-SNAPSHOT -Drevision=$tagName clean deploy"

                        }
                    }
                }
            }


            stage('Build master') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        withMaven(mavenSettingsConfig: 'navMavenSettings') {

                            buildEnvironment = new buildEnvironment()
                            if (maven.javaVersion() != null) {
                                buildEnvironment.overrideJDK(maven.javaVersion())
                            }

                            sh "mvn -B -Dfile.encoding=UTF-8 -DdeployAtEnd=true -Dsha1= -Dchangelist= -Drevision=$version clean deploy"
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
                    fpgithub.updateBuildStatus(ARTIFACTID, "success", GIT_COMMIT_HASH_FULL)
                }
            }
            failure {
                script {
                    fpgithub.updateBuildStatus(ARTIFACTID, "failure", GIT_COMMIT_HASH_FULL)
                }
            }
        }
    }
}