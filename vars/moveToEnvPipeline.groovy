import no.nav.jenkins.*

def call () {
  
    def fromNs
    def toNs
    def k8DeployGitURL = [fpformidling:     'git@fp-formidling.github.com:navikt/fp-formidling.git', 
                          spberegning:      'git@spberegning.github.com:navikt/spberegning.git', 
                          fpabakus:         'git@fp-abakus.github.com:navikt/fp-abakus.git',
                          fpfordel:         'git@fpfordel.github.com:navikt/fpfordel.git',
                          "fpsak-frontend": 'git@fpsak-frontend.github.com:navikt/fpsak-frontend.git']
        
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
            booleanParam(defaultValue: true, description: '', name: 'fpabonnent')
            booleanParam(defaultValue: true, description: '', name: 'fpfordel')
            booleanParam(defaultValue: true, description: '', name: 'fplos')
            booleanParam(defaultValue: false, description: '', name: 'fpoppdrag')
            booleanParam(defaultValue: false, description: '', name: 'fptilbake')
            booleanParam(defaultValue: false, description: '', name: 'fprisk')
            booleanParam(defaultValue: false, description: '', name: 'fpinfo')
            booleanParam(defaultValue: false, description: '', name: 'spberegning')
            booleanParam(defaultValue: false, description: '', name: 'fpformidling')
            //booleanParam(defaultValue: false, description: '', name: 'fpsak-frontend')
            //booleanParam(defaultValue: true, description: '', name: 'fpabakus')
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
                                deployJira('fpsak', fromNs, toNs)
                              }
                          }
                       }
                    } 
                    stage('fpabonnent') { 
                      agent any
                      steps {
                          script {
                              if (params.fpabonnent) {
                                deployJira('fpabonnent', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpfordel') { 
                      agent any
                      steps {
                          script {
                              if (params.fpfordel) {
                                deployk8('fpfordel', fromNs, toNs, k8DeployGitURL.get('fpfordel'))
                              }
                          }
                       }
                    }
                    stage('fplos') { 
                      agent any
                      steps {
                          script {
                              if (params.fplos) {
                                deployJira('fplos', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpoppdrag') { 
                      agent any
                      steps {
                          script {
                              if (params.fpoppdrag) {
                                deployJira('fpoppdrag', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fptilbake') { 
                      agent any
                      steps {
                          script {
                              if (params.fptilbake) {
                                deployJira('fptilbake', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fprisk') { 
                      agent any
                      steps {
                          script {
                              if (params.fprisk) {
                                deployJira('fprisk', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpinfo') { 
                      agent any
                      steps {
                          script {
                              if (params.fpinfo) {
                                deployJira('fpinfo', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpformidling') { 
                      agent any
                      steps {
                          script {
                              if (params.fpformidling) {
                                deployk8('fpformidling', fromNs, toNs, k8DeployGitURL.get('fpformidling'))
                              }
                          }
                       }
                    }
                    stage('spberegning') { 
                      agent any
                      steps {
                          script {
                              if (params.spberegning) {
                                deployk8('spberegning', fromNs, toNs, k8DeployGitURL.get('spberegning'))
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
        checkout([
                           $class: 'GitSCM',
                           branches: [[name: 'refs/heads/master']],
                           doGenerateSubmoduleConfigurations: false,
                           userRemoteConfigs: [[credentialsId: '', url: scmURL]]
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
                    slackMessage("_Deploy av $artifactId:$version til $to var suksessfult._", msgColor) 
                }
            }                        
        } else {
          error('Deploy av $artifactId feilet! Fant ikke katalogen k8.')
        }
    }    
}

def slackMessage(message, msgColor) {
  slackSend(color: "$msgColor", channel: "#foreldrepenger-ci", message: "$message")
}
