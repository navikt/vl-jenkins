
def call() {

    pipeline {
        agent {
            node {
                label 'MASTER'
            }
        }

        options {
            timestamps()
        }

        parameters {
            booleanParam(defaultValue: true, description: '', name: 'fpsak', )
            booleanParam(defaultValue: true, description: '', name: 'fpfordel')
            booleanParam(defaultValue: true, description: '', name: 'fpabonnent')
            booleanParam(defaultValue: true, description: '', name: 'fpoppdrag')
            booleanParam(defaultValue: true, description: '', name: 'fptilbake')
            booleanParam(defaultValue: true, description: '', name: 'fpsak-frontend')
            booleanParam(defaultValue: true, description: '', name: 'fpformidling')

        }

        stages {
            stage('Git-diff') {
                steps {
                    script {

                      msgColor = "#117007"
                      fraNs = "q1"
                      tilNs = "default"

                      message = "Endringer til P:\n"

                      def slackBaseURL= "https://stash.adeo.no/projects/VEDFP/repos"
                      def githubBaseURL = "https://github.com/navikt"

                      def gitRepoApps = ["fpsak-frontend":'fpsak-frontend', fpformidling:'fp-formidling', fpoppdrag:'fpoppdrag', fptilbake:'fptilbake', fplos:'fplos', fpabakus:'fp-abakus']
                      def stashRepoApps = [fpsak:'vl-foreldrepenger', fpfordel:'vl-fordel', fpabonnent:'vl-fpabonnent']

                      def keys = params.keySet().sort() as List
                      for ( int i = 0; i < keys.size(); i++ ) {
                          app = keys[i]

                          if(params.get(app)) {
                              preprodVersion = getAppVersion("preprod-fss", fraNs, app)
                              prodVersion = getAppVersion("prod-fss", tilNs, app)
                              message += "\n $app "
                              if (preprodVersion == prodVersion) {
                                  message += " [=]\n"
                              } else {
                                  message += " [>]\n"
                              }
                              if (gitRepoApps.containsKey(app)) {
                                  message += githubBaseURL + "/${gitRepoApps.get(app)}/compare/${prodVersion}...${preprodVersion}"
                              } else {
                                message += slackBaseURL + "/${stashRepoApps.get(app)}/compare/commits?targetBranch=refs%2Ftags%2F${prodVersion}&sourceBranch=refs%2Ftags%2F${preprodVersion}"
                              }
                              message += "\n"
                          }
                      }

                      slackMessage(message, msgColor)

                    }
                }
            }
        }
    }
}


def getAppVersion(context, ns, appl) {
    def version

    sh "k config use-context $context"

    def versions = sh(
       script: "k get pods -l app=${appl} -n${ns} -o jsonpath='{.items[*].spec.containers[*].env[?(@.name==\"APP_VERSION\")].value}'|tr -d '%'",
       returnStdout: true
    ).trim().split()

    if (versions) {
      versions = versions.toUnique()
      echo "versions: $ns $appl $versions"

      if (versions.size() > 1) {
        message = "Endringer til P: $appl har feilende poder i $ns, sjekk!! "
        slackMessage(message, msgColor)
      } else if (versions.size() == 1) {
        version = versions.first()
      }
    } else {
      message = "Endringer til P: $appl har ingen kjørende poder i $ns."
      slackMessage(message, msgColor)
    }

    return version
}

def slackMessage(message, msgColor) {
  slackSend(color: "$msgColor", channel: "#fp-go-no-go", message: "$message")
}
