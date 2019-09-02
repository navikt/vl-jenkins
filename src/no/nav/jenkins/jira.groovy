package no.nav.jenkins

def deployNais(String artifactId, String version, String miljo) {
    
    def callbackUrl = "${env.BUILD_URL}input/AfterDeploy"

    String fssZoneId = "23451"
    def naisDeployJiraPayload
    def environmentId = environmentId(miljo)


    try {
        String msgColor = "#077040"
        slackInfo("Deploy av *" + artifactId + "*:" + version + " til *" + miljo + '*')
        echo("Attempting to deploy $artifactId:$version to $miljo (JIRA ID=$environmentId), using the callbackURL $callbackUrl")

        if (miljo == "p") {
            //i prod skal vi deploye til default namespace ikke til p
            naisDeployJiraPayload = naisDeployPayload(callbackUrl, environmentId, artifactId, version, fssZoneId, "default")
        } else {
            naisDeployJiraPayload = naisDeployPayload(callbackUrl, environmentId, artifactId, version, fssZoneId, miljo)
        }

        def jiraIssueId = createJiraTask(naisDeployJiraPayload)

        String selftest = "$artifactId-${miljo}.nais.preprod.local/$artifactId/internal/selftest"
        String naisSelftestLink = "<a href=\"https://$selftest\">$miljo</a>"
        
        String jiraLink = "<a href=\"https://jira.adeo.no/browse/$jiraIssueId\">$jiraIssueId</a>"

        echo "selftest: $naisSelftestLink"
        echo "Deploy: $version til $miljo $jiraLink"

        input([id: "AfterDeploy", message: "Waiting for remote Jenkins server to deploy the application..."])

        slackInfo(msgColor, "_Deploy av $artifactId:$version til $miljo var suksessfult._")

    } catch (error) {
        slackError("Dette gjelder ${artifactId} til: ${miljo}")
        throw error
    }
}

def naisDeployPayload(String callbackUrl,
         String environmentId,
         String artifactId,
         String version,
         String zone,
         String miljo) {

    def postBody = [
            fields: [
                    project          : [key: "DEPLOY"],
                    issuetype        : [id: "14302"],
                    customfield_14811: [id: "$environmentId", value: "$environmentId"],
                    customfield_14812: "$artifactId:$version",
                    customfield_17410: callbackUrl,
                    customfield_19015: [id: "22707", value: "Yes"],
                    customfield_19413: "$miljo", //k8s namespace
                    customfield_19610: [id: "$zone", value: "$zone"],
                    summary          : "Automatisk deploy av $artifactId:$version til $miljo"
            ]
    ]

    return groovy.json.JsonOutput.toJson(postBody)
}

def createJiraTask(Object payload) {
    withCredentials([[$class: "UsernamePasswordMultiBinding",
                      credentialsId: 'jiraServiceUser',
                      usernameVariable: "JIRA_USERNAME",
                      passwordVariable: "JIRA_PASSWORD"]]) {

        def jiraAuthToken = "${env.JIRA_USERNAME}:${env.JIRA_PASSWORD}".bytes.encodeBase64().toString()
        echo "jiraAuthToken: $jiraAuthToken"

        def response = httpRequest([
                url                   : "https://jira.adeo.no/rest/api/2/issue/",
                customHeaders         : [[name: "Authorization", value: "Basic $jiraAuthToken"]],
                consoleLogResponseBody: true,
                contentType           : "APPLICATION_JSON",
                httpMode              : "POST",
                requestBody           : payload
        ])

        def jiraIssueId = readJSON([text: response.content])["key"].toString()
        return jiraIssueId
    }
}

def environmentId(String environmentName) {
    def environmentMap = [
            "t4": "16560",
            "q0": "16824",
            "q1": "16825",
            "p": "17658"
    ]

    def lowerCaseEnvironmentName = environmentName.toLowerCase()
    return environmentMap[lowerCaseEnvironmentName].toString()
}

def slackError(String tilleggsinfo) {
    slackSend color: "danger", channel: "#fp-ci-test",  message: "${env.JOB_NAME} [${env.BUILD_NUMBER}] feilet: ${env.BUILD_URL} ${tilleggsinfo}"
}

def slackInfo(String msg) {
    slackInfo("#595959", msg)
}

def slackInfo(String color, String msg) {
    slackSend color: color, channel: "#fp-ci-test", message: msg
}
