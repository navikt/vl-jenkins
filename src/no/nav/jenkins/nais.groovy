package no.nav.jenkins

def getAppVersion(context, ns, appl) {
    def version
    def dockerRegistryIapp = "repo.adeo.no:5443"
    def dockerRegistryNav = "navikt"
    def msg
    
    sh "k config use-context $context"

    def contImages = sh(
       script: "k get pods -l app=${appl} -n${ns} -o jsonpath='{.items[*].spec.containers[*].image}'|tr -d '%'",
       returnStdout: true
    ).trim()
    versions = contImages.replaceAll("$dockerRegistryIapp/$appl:", "").replaceAll("$dockerRegistryNav/$appl:", "").split()

    if (versions) {
      versions = versions.toUnique()
      echo "versions: $ns $appl $versions"

      if (versions.size() > 1) {
        msg = "Endringer til P: $appl har feilende poder i $context $ns, sjekk!! "
      } else if (versions.size() == 1) {
        version = versions.first()
      }
    } else {
      msg = "Endringer til P: $appl har ingen kjørende poder i $context $ns."
    }

    return [version, msg]
}
