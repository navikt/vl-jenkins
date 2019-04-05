package no.nav.jenkins

def updateBuildStatus(String githubRepoName, String status, String commitSHA) {
    GITHUB_APP_ID = "20250"
    checkout([
                        $class: 'GitSCM',
                        branches: [[name: 'refs/heads/master']],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'vl-jenkins']],
                        submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: '', url: 'https://github.com/navikt/vl-jenkins.git']]
            ])

    withCredentials( [file(credentialsId: 'foreldrepenger-gihub-app', variable: 'MY_SECRET_FILE')]) {
        JWT_TOKEN = sh (script: "vl-jenkins/bin/generate-jwt.sh ${env.MY_SECRET_FILE} 20250", returnStdout: true)
        //echo ("JWT_TOKEN $JWT_TOKEN")
        GH_TOKEN = sh (script: "vl-jenkins/bin/generate-installation-token.sh $JWT_TOKEN", returnStdout: true)
        //echo ("GH_TOKEN $GH_TOKEN");
    }
    sh "curl --proxy http://webproxy-internett.nav.no:8088 -H \"Content-Type: application/json\" -X POST -d '{\"state\": \"$status\", \"context\": \"continuous-integration/jenkins\", \"description\": \"Jenkins\", \"target_url\": \"${env.BUILD_URL}\"}' 'https://api.github.com/repos/navikt/$githubRepoName/statuses/$commitSHA?access_token=$GH_TOKEN'"
}

return this
