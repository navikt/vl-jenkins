import no.nav.jenkins.*

def call() {

  String mvnVersionParams = ''

    pipeline {
        agent { label 'BUILD' }

        parameters {
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_TO_SSH_CLONE_URL')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_FROM_HASH')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_ID')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_FROM_BRANCH')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_FROM_REPO_SLUG')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_URL')
                string(defaultValue: '', description: '', name: 'PULL_REQUEST_USER_EMAIL_ADDRESS')
                booleanParam(defaultValue: false, description: 'Maven parameter (-U)', name: 'UPDATE_ARTIFACT')
        }

        options {
            timestamps()
        }
        environment {
            LANG = "nb_NO.UTF-8"
        }
        stages {
            stage ('Merge') {
                steps {
                    script {
                        step([$class: 'WsCleanup'])
                        notify(params.PULL_REQUEST_FROM_HASH)

                        checkout changelog: true, poll: true, scm:
                            [
                                $class          : 'GitSCM',
                                branches        : [[name   : params.PULL_REQUEST_FROM_HASH]],
                                extensions      : [
                                        [$class : 'PreBuildMerge',
                                         options: [
                                                 mergeStrategy  : 'DEFAULT',
                                                 fastForwardMode: 'FF',
                                                 mergeRemote    : 'origin',
                                                 mergeTarget    : 'master'
                                         ]
                                        ]],
                                userRemoteConfigs:
                                        [[
                                                 credentialsId: 'deployer',
                                                 url          : params.PULL_REQUEST_TO_SSH_CLONE_URL
                                         ]]
                            ]
                     }
                }
            }

            stage("Init") {
                steps {
                    script {
                        maven = new maven()
                        buildEnvironment = new buildEnvironment()

                        buildEnvironment.setEnv()
                        if (maven.javaVersion() != null) {
                            buildEnvironment.overrideJDK(maven.javaVersion())
                        }

                        commitHash = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                        timestamp = new Date().format("yyyyMMddHHmmss")
                        String sha = "_${timestamp}_${commitHash}"
                        String revision = maven.revision()
                        String version = revision + sha
                        mvnVersionParams = "-Drevision=$revision -Dchangelist= -Dsha1=$sha"

                        //String description = "<a href='${params.PULL_REQUEST_URL}'>${params.PULL_REQUEST_FROM_REPO_SLUG}-${params.PULL_REQUEST_FROM_BRANCH}-${params.PULL_REQUEST_ID}</a>"
                        //console.info(description)
                    }
                }
            }

            stage("Build") {
                steps {
                    script {
                        String mavenFlagg = ""
                        if (params.UPDATE_ARTIFACT) {
                            mavenFlagg = "-U"
                        }
                        String branchNavn = org.apache.commons.lang.RandomStringUtils.random(5, true, true)
                        String schemaNavnFPSAK = "fpsak_unit_" + branchNavn
                        String schemaNavnFPSAK_HIST = "fpsak_hist_unit_" + branchNavn
                        String artifactId = maven.artifactId().toLowerCase()

                        echo "-------------SchemaNavn: $branchNavn -------------"

                        configFileProvider(
                                [configFile(fileId: 'navMavenSettingsPkg', variable: 'MAVEN_SETTINGS')]) {

                            env.MAVEN_OPTS = "-Xms256m -Xmx512m"
                            String mavenProperties = maven.properties()
                            String flywayConig = " -Dflyway.placeholders.vl_${artifactId}_hist_schema_unit=$schemaNavnFPSAK_HIST -Dflyway.placeholders.vl_${artifactId}_schema_unit=$schemaNavnFPSAK"

                            sh "export APPDATA=web/klient/node/node_modules/npm/bin;" +
                                    " mvn $mavenFlagg -B -s $MAVEN_SETTINGS $mavenProperties $mvnVersionParams $flywayConig" +
                                    " clean install"
                        }
                    }
                }
                post {
                    success {
                        script {
                          currentBuild.result = 'SUCCESS'
                          notify(params.PULL_REQUEST_FROM_HASH)
                        }
                    }
                    failure {
                        script {
                          currentBuild.result = 'FAILURE'
                          notify(params.PULL_REQUEST_FROM_HASH)
                        }
                    }
                }
            }
        }
        
    }
}

def notify(String pullRequestFromHash) {
    notifyStash commitSha1          : pullRequestFromHash,
            credentialsId                   : "mayu",
            disableInprogressNotification   : false,
            considerUnstableAsSuccess       : true,
            ignoreUnverifiedSSLPeer         : true,
            includeBuildNumberInKey         : false,
            prependParentProjectKey         : false,
            projectKey                      : "",
            stashServerBaseUrl              : "http://stash.adeo.no"
}
