import no.nav.jenkins.*

def call() {
    def maven = new maven()
    def appName = ''
    def exitCode
    def naisAppImage = ''
    def deployedVersionTag = ''

    pipeline {
        agent none
        stages {
            stage('Hent appName') {
                agent { label 'master' }
                steps {
                    script {
                        if (fileExists('pom.xml')) {
                            appName = maven.artifactId()
                        }
                        else {
                            appName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                        }
                    }
                }
            }
            stage('Rollback oppsummering') {
                agent { label 'master' }
                steps {
                    script {
                        dockerRegistryIapp = "repo.adeo.no:5443"
                        sh "familie-kubectl config use-context dev-fss"
                        naisAppImage = sh(script: "familie-kubectl get deployments -l app=$appName -o jsonpath='{.items[0].spec.template.spec.containers[0].image}'", returnStdout: true)
                        deployedVersionTag = naisAppImage - "$dockerRegistryIapp/$appName:"

                        echo "* * * * * * * * * * * * * * * * * * * * * * * * * * *"
                        echo "Valgt versjon: $versionToDeploy"
                        echo "Valgt miljø: $deployToEnvironment"
                        if (versionToDeploy == deployedVersionTag) {
                            echo "ADVARSEL: Valgt versjon er lik deployet versjon!"
                        }
                        echo "* * * * * * * * * * * * * * * * * * * * * * * * * * *"
                    }
                }
            }
            stage('Godkjenn deploy av versjon') {
                agent none
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        input message: 'Vil du deploye valgt versjon til valgt miljø?', ok: 'Ja, jeg vil deploye :)'
                    }
                }
            }
            stage('Deploy versjon til preprod') {
                agent { label 'master' }
                when {
                    beforeAgent true
                    expression {
                        return deployToEnvironment == 'preprod'
                    }
                }
                steps {
                    script {
                        sh "familie-kubectl config use-context dev-fss"
                        sh "sed \'s/RELEASE_VERSION/$versionToDeploy/g\' app-preprod.yaml | familie-kubectl apply -f -"

                        exitCode = sh returnStatus: true, script: "familie-kubectl rollout status deployment/$appName"

                        if (exitCode != 0) {
                            throw new RuntimeException("Deploy av $appName, versjon $versionToDeploy til $deployToEnvironment feilet")
                        }
                    }
                }
            }
            stage('Deploy versjon til prod') {
                agent { label 'master' }
                when {
                    beforeAgent true
                    expression {
                        return deployToEnvironment == 'prod'
                    }
                }
                steps {
                    script {
                        sh "familie-kubectl config use-context prod-fss"
                        sh "sed \'s/RELEASE_VERSION/$versionToDeploy/g\' app-prod.yaml | familie-kubectl apply -f -"

                        exitCode = sh returnStatus: true, script: "familie-kubectl rollout status deployment/$appName"

                        if (exitCode != 0) {
                            throw new RuntimeException("Deploy av $appName, versjon $versionToDeploy til $deployToEnvironment feilet")
                        }
                    }
                }
            }
        }
    }
}
