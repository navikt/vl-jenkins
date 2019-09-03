import no.nav.jenkins.*

def call () {
  
    def fromNs
    def toNs
    
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
            booleanParam(defaultValue: true, description: '', name: 'fpinfo')
            booleanParam(defaultValue: false, description: '', name: 'spberegning')
            //booleanParam(defaultValue: true, description: '', name: 'fpsak-frontend')
            //booleanParam(defaultValue: true, description: '', name: 'fpformidling')
            //booleanParam(defaultValue: true, description: '', name: 'fpabakus')
        }
        
        stages {
            stage ('Init') {
              steps {
                  script {
                      fromNs = params.get("FROM_ENVIRONMENT").trim()
                      toNs = params.get("TO_ENVIRONMENT").trim()

                      if (fromNs.length()*toNs.length() == 0) {
                          echo "FROM_ENVIRONMENT og TO_ENVIRONMENT må ha verdi!"
                          exit 1
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
                                deployNais('fpsak', fromNs, toNs)
                              }
                          }
                       }
                    } 
                    stage('fpabonnent') { 
                      agent any
                      steps {
                          script {
                              if (params.fpabonnent) {
                                deployNais('fpabonnent', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpfordel') { 
                      agent any
                      steps {
                          script {
                              if (params.fpfordel) {
                                deployNais('fpfordel', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fplos') { 
                      agent any
                      steps {
                          script {
                              if (params.fplos) {
                                deployNais('fplos', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpoppdrag') { 
                      agent any
                      steps {
                          script {
                              if (params.fpoppdrag) {
                                deployNais('fpoppdrag', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fptilbake') { 
                      agent any
                      steps {
                          script {
                              if (params.fptilbake) {
                                deployNais('fptilbake', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fprisk') { 
                      agent any
                      steps {
                          script {
                              if (params.fprisk) {
                                deployNais('fprisk', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('fpinfo') { 
                      agent any
                      steps {
                          script {
                              if (params.fpinfo) {
                                deployNais('fpinfo', fromNs, toNs)
                              }
                          }
                       }
                    }
                    stage('spberegning') { 
                      agent any
                      steps {
                          script {
                              if (params.spberegning) {
                                deployNais('spberegning', fromNs, toNs)
                              }
                          }
                       }
                    }
                }
            }
        }
    }
}

def deployNais(String artifactId, String from, String to ) {
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

def slackMessage(message, msgColor) {
  slackSend(color: "$msgColor", channel: "#fp-ci-test", message: "$message")
}
