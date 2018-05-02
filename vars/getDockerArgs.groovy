// make sure the kvm group gid is passed to docker with option --group-add
def call() {
  def GROUP = null
  GROUP = sh(returnStdout: true, script: 'getent group kvm | cut -d : -f 3').trim()
  if (GROUP == "") {
    // defaults to user group gid
    GROUP = sh(returnStdout: true, script: 'id -u').trim()
  }
  return "--group-add " + "${GROUP}"
}
