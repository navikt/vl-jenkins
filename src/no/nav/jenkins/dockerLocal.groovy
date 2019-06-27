package no.nav.jenkins


import no.nav.jenkins.*

def sjekkLokalApplikasjonStatus(String selftestUrl){
    def requestString = "http://localhost:8080"+selftestUrl
    return sjekkHttp200(requestString)
}

def sjekkLokalVtpStatus(){
    def requestString = "http://localhost:8060/rest/isReady"
    return sjekkHttp200(requestString)
}

def sjekkHttp200(String requestString){
    try{
        def response = httpRequest(url: requestString, timeout: 15)
        println("Status: " + response.status)
        println("Content: " + response.content)

        if(response.status == 200){
            println "Response fra $requestString ok"
            return true
        } else {
            println "Response fra VTP feilet"
            return false
        }
    } catch (Exception e){
        return false
    }
}
