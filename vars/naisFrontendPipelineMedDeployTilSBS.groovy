import no.nav.jenkins.*

def call() {
    def dockerRegistryIapp=''
    def githubRepoName=''
    def tagName=''
    def exitCode
    pipeline {
        agent none
        stages {
            stage('Checkout Tags') { // checkout only tags.
                agent { label 'master' }
                steps {
                    script {
                        dockerRegistryIapp = "repo.adeo.no:5443"
                        checkout scm
                        gitCommitHash = sh(script: "git log -n 1 --pretty=format:'%h'", returnStdout: true)
                        tagName = new Date().format("YYYYMMddHHmmss") + "_" + gitCommitHash
                        githubRepoName = sh(script: "basename -s .git `git config --get remote.origin.url`", returnStdout: true).trim()
                    }
                }
            }
            stage('Build and Push'){
                agent { label 'master' }
                steps {
                    sh 'PATH=$PATH:/usr/local/lib/node/nodejs/bin:/opt/yarn-v1.12.3/bin; yarn install --ignore-scripts'
                    sh 'PATH=$PATH:/usr/local/lib/node/nodejs/bin:/opt/yarn-v1.12.3/bin; yarn build'
                    sh "docker build -t $dockerRegistryIapp/$githubRepoName:$tagName -f Dockerfile ./ --build-arg HTTPS_PROXY='' --build-arg HTTP_PROXY=''"
                    sh "docker tag $dockerRegistryIapp/$githubRepoName:$tagName $dockerRegistryIapp/$githubRepoName:latest"
                    sh "docker push $dockerRegistryIapp/$githubRepoName:$tagName"
                }
            }
            stage('Tag master') {
                agent { label 'master' }
                when {
                    branch 'master'
                }
                steps {
                    sh "git tag $tagName -m $tagName"
                    sh "git push origin --tag"
                }
            }
            stage('Deploy master til preprod') {
                agent { label 'master' }
                when {
                    branch 'master'
                }
                steps {
                    script {
                        sh "familie-kubectl config use-context dev-sbs"
                        sh "sed \'s/RELEASE_VERSION/$tagName/g\' app-preprod.yaml | familie-kubectl apply -f -"

                        exitCode = sh returnStatus: true, script: "familie-kubectl rollout status deployment/$githubRepoName"

                        if (exitCode != 0) {
                            throw new RuntimeException("Deploy av $githubRepoName, versjon $tagName til preprod-sbs feilet")
                        }
                    }
                }
            }
            stage('Godkjenn deploy av master til prod') {
                agent none
                when {
                    beforeInput true
                    branch 'master'
                }
                steps {
                    timeout(time: 3, unit: 'DAYS') {
                        input message: 'Vil du deploye master til prod?', ok: 'Ja, jeg vil deploye :)'
                    }
                }
            }
            stage('Deploy master til prod') {
                agent { label 'master' }
                when {
                    branch 'master'
                }
                steps {
                    script {
                        sh "familie-kubectl config use-context prod-sbs"
                        sh "sed \'s/RELEASE_VERSION/$tagName/g\' app-prod.yaml | familie-kubectl apply -f -"

                        exitCode = sh returnStatus: true, script: "familie-kubectl rollout status deployment/$githubRepoName"

                        if (exitCode != 0) {
                            throw new RuntimeException("Deploy av $githubRepoName, versjon $tagName til prod-sbs feilet")
                        }
                    }
                }
            }
            stage('Godkjenn deploy av branch til preprod') {
                agent none
                when {
                    beforeInput true
                    not {
                        branch 'master'
                    }
                }
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        input message: 'Vil du deploye branch til preprod?', ok: 'Ja, jeg vil deploye :)'
                    }
                }
            }
            stage('Deploy branch til preprod') {
                agent { label 'master' }
                when {
                    not {
                        branch 'master'
                    }
                }
                steps {
                    script {
                        sh "familie-kubectl config use-context dev-sbs"
                        sh "sed \'s/RELEASE_VERSION/$tagName/g\' app-preprod.yaml | familie-kubectl apply -f -"

                        exitCode = sh returnStatus: true, script: "familie-kubectl rollout status deployment/$githubRepoName"

                        if (exitCode != 0) {
                            throw new RuntimeException("Deploy av $githubRepoName, versjon $tagName til preprod-sbs feilet")
                        }
                    }
                }
            }
        }
    }
}
