import no.nav.jenkins.*

def call(body) {
    def config = [:]

    timestamps {
        properties([disableConcurrentBuilds(), parameters([
                string(defaultValue: '', description: 'Applikasjon SUT, f.eks fpsak', name: 'application'),
                string(defaultValue: '', description: 'SUT Versjon', name: 'version'),
                string(defaultValue: '', description: 'Overstyr profil - default er applikasjonsnavn', name: 'profil'),
                string(defaultValue: '', description: 'Changelog fra upstream job', name: 'changelog'),
                booleanParam(defaultValue: false, description: 'Clean av SUT databasen', name: 'clean'),
                booleanParam(defaultValue: false, description: 'Releasekandidat?', name: 'rc'),
                booleanParam(defaultValue: false, description: 'Test av pipe', name: 'pipetest')
        ])
        ])


        params = [
                [$class: 'StringParameterValue', name: 'applikasjon', value: application],
                [$class: 'StringParameterValue', name: 'applikasjonVersjon', value: version],
                [$class: 'StringParameterValue', name: 'profil', value: profil],
                [$class: 'StringParameterValue', name: 'changelog', value: changelog],
                [$class: 'BooleanParameterValue', name: 'rc', value: rc],
                [$class: 'BooleanParameterValue', name: 'clean', value: clean],
                [$class: 'BooleanParameterValue', name: 'pipetest', value: pipetest],
        ]

        supportedApps = ["fpsak","spberegning","fprisk"]


        node('VTPAUTOTEST') {
            try {
                env.LANG = "nb_NO.UTF-8"

                println("Parametere:")
                println(params.toString())

                println("Changelog:")
                println(changelog)


                stage("Starter test for applikasjon") {
                    println("Starter applikasjon: ${application} med versjon: ${version}")
                    if (supportedApps.contains(application)) {
                        if(pipetest == 'true'){
                            println("Kjører test av ny pipeline")
                            build job: "autotest-${application}-new", parameters: params
                        } else {
                            build job: "autotest-${application}", parameters: params
                        }
                    } else {
                        println("Applikasjonen ${application} støttes ikke")
                    }
                }


            } catch (Exception e) {
                println("Bygg feilet: $e")
                currentBuild.result('FAILURE')
            }

        }
    }
}
