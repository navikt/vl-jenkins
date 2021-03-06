import no.nav.jenkins.*

def call () {

    def fromNs
    def toNs
    def k8DeployGitURL = [fpsak:            'ssh://git@stash.adeo.no:7999/vedfp/vl-foreldrepenger.git',
                          fpoppdrag:        'git@fpoppdrag.github.com:navikt/fpoppdrag.git',
                          fptilbake:        'git@fptilbake.github.com:navikt/fptilbake.git',
                          "fpsak-frontend": 'git@fpsak-frontend.github.com:navikt/fpsak-frontend.git',
                          testhub:          'git@testhub.github.com:navikt/testhub.git']

    pipeline {
        agent {
            node { label 'MASTER' }
        }

        options {
            timestamps()
        }

        parameters {
            string(defaultValue: '', description: 't4', name: 'FROM_ENVIRONMENT')
            string(defaultValue: '', description: '', name: 'TO_ENVIRONMENT')

            booleanParam(defaultValue: true, description: '', name: 'fpsak')
            booleanParam(defaultValue: false, description: '', name: 'fpoppdrag')
            booleanParam(defaultValue: false, description: '', name: 'fptilbake')
            booleanParam(defaultValue: false, description: '', name: 'testhub')
        }

        stages {
            stage ('Init') {
              steps {
                  script {
                      fromNs = params.get("FROM_ENVIRONMENT").trim().toLowerCase()
                      toNs = params.get("TO_ENVIRONMENT").trim().toLowerCase()

                      if (fromNs.length()*toNs.length() == 0) {
                          echo "FROM_ENVIRONMENT og TO_ENVIRONMENT må ha verdi!"
                          error('FROM_ENVIRONMENT og TO_ENVIRONMENT må ha verdi!')
                          return
                      }
                    }
                }
            }
            stage('Flytt') {
                parallel  {
                    stage('fpsak') {
                      agent any
                      steps {
                          script {
                              if (params.fpsak) {
                                deployk8('fpsak', fromNs, toNs, k8DeployGitURL.get('fpsak'))
                              }
                          }
                       }
                    }
                    stage('fpoppdrag') {
                      agent any
                      steps {
                          script {
                              if (params.fpoppdrag) {
                                deployk8('fpoppdrag', fromNs, toNs, k8DeployGitURL.get('fpoppdrag'))
                              }
                          }
                       }
                    }
                    stage('fptilbake') {
                      agent any
                      steps {
                          script {
                              if (params.fptilbake) {
                                deployk8('fptilbake', fromNs, toNs, k8DeployGitURL.get('fptilbake'))
                              }
                          }
                       }
                    }
                    stage('testhub') {
                      agent any
                      steps {
                          script {
                              if (params.testhub) {
                                deployk8('testhub', fromNs, toNs, k8DeployGitURL.get('testhub'))
                              }
                          }
                       }
                    }
                }
            }
        }
    }
}

def deployJira(String artifactId, String from, String to ) {
    nais = new nais()
    def context = "preprod-fss"
    msgColor = "#117007"

    if (from == "p") {
        context = "prod-fss"
    }

      echo "Flytter $artifactId fra $from til $to"

    def (version, msg) = nais.getAppVersion(context, from, artifactId)

    echo "version: $version"
    echo "msg: $msg"

    if (msg && msg.length() > 0) {
        slackMessage(msg, msgColor)
    } else if (version && version.length() > 0 ) {
        echo "Flytter $artifactId:$version fra $from til $to"

        jira = new jira()
        jira.deployNais(artifactId, version, to)
    } else {
        msg = "Flytting av $artifactId fra $from til $to feilet!"
        echo "$msg ..."
        slackMessage(msg, msgColor)
    }

}

def deployk8(String artifactId, String from, String to, String scmURL) {
    nais = new nais()
    msgColor = "#117007"
    def context =  (from == "p") ? "prod-fss": "preprod-fss"

    echo "Flytter $artifactId fra $from til $to"

    def (version, msg) = nais.getAppVersion(context, from, artifactId)

    if (msg && msg.length() > 0) {
      msg = "Flytting av $artifactId fra $from til $to feilet!"
      echo "$msg ..."
      slackMessage(msg, msgColor)
    } else if (version && version.length() > 0 ) {
        def credsId = '';
        if (artifactId == "fpsak") {
          credsId = 'ssh-jenkins-user'
        }

        checkout([
          $class: 'GitSCM',
          branches: [[name: 'refs/heads/master']],
          doGenerateSubmoduleConfigurations: false,
          userRemoteConfigs: [[credentialsId: credsId, url: scmURL]]
          ])

        if (fileExists ('k8s')) {
            dir('k8s') {
                slackMessage("Deploy av *" + artifactId + "*:" + version + " til *" + to + '*', msgColor)
                def props = readProperties interpolate: true, file: "application.${to}.variabler.properties"
                def value = "s/RELEASE_VERSION/${version}/g"
                props.each { k, v -> value = value + ";s%$k%$v%g" }
                sh "k config use-context $props.CONTEXT_NAME"
                sh "sed \'$value\' app.yaml | k apply -f -"

                def naisNamespace = (to == "p") ? "default" : to

                def exitCode = sh returnStatus: true, script: "k rollout status -n${naisNamespace} deployment/${artifactId}"
                echo "exit code is $exitCode"

                if (exitCode == 0) {
                    slackMessage("_Deploy av $artifactId:$version til $to var vellykket._", msgColor)
                } else {
                    slackError("_Deploy av $artifactId:$version til $to var feilet._")   
                }
            }
        } else {
          error('Deploy av $artifactId feilet! Fant ikke katalogen k8s.')
        }
    }
}

def slackError(String tilleggsinfo) {
    slackSend color: "danger", channel: "#foreldrepenger-ci",  message: "${env.JOB_NAME} [${env.BUILD_NUMBER}] feilet: ${env.BUILD_URL} ${tilleggsinfo}"
}

def slackMessage(message, msgColor) {
  slackSend(color: "$msgColor", channel: "#foreldrepenger-ci", message: "$message")
}
