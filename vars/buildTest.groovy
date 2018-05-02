/* TODO:
- Harcoded paths for both Dockerfiles
- Check debosFile file exists before running MakeImageStep
*/
def call(Closure context) {

    def config = [:]
    context.resolveStrategy = Closure.DELEGATE_FIRST
    context.delegate = config
    context()

    def pipeline_version = setPipelineVersion()
    def name = config.name
    def kernel_arch = config.archList
    def debian_arch =  ["arm64": "armhf",
                        "armel": "arm64",
                        "x86": "i386",
                        "x86_64": "amd64"]
    def toolchain_arch =  ["arm64": "arm-linux-gnueabihf",
                            "armel": "aarch64-linux-gnu",
                            "x86": "i386-linux-gnu",
                            "x86_64": "x86_64-linux-gnu"]
    def debosFile = config.debosFile
    def buildTestScript = config.build_test_suite

    // debos will always run the extra packages step, so let's mak sure it has something to install
    def extraPackages = "bash"
    if (config.extra_packages != null) {
        extraPackages = config.extra_packages
    }

    def stepsForParallel = [:]
    for (int i = 0; i < kernel_arch.size(); i++) {
        def arch = kernel_arch[i]
        def buildStep = "Build image for ${arch}"
        stepsForParallel[buildStep] = makeImageStep(pipeline_version,
                                                    arch,
                                                    debian_arch[arch],
                                                    debosFile,
                                                    extraPackages)

        if (buildTestScript != null) {
            buildStep = "Build test suite ${name} for ${arch}"
            stepsForParallel[buildStep] = makeBuildStep(pipeline_version,
                                                        arch,
                                                        debian_arch[arch],
                                                        toolchain_arch[arch],
                                                        name,
                                                        buildTestScript)
        }

    }

    parallel stepsForParallel

    node {
        try {

            if (buildTestScript != null) {
                for (int i = 0; i < kernel_arch.size(); i++) {
                    def arch = kernel_arch[i]
                    stage("Adding test suite ${name} to image for ${arch}") {
                        unstash "rootfs-${arch}"
                        unstash "built-test-${arch}"
                        dir ("${pipeline_version}/${arch}") {
                        sh "gunzip rootfs-${arch}.cpio.gz"
                        dir("ramdisk") {
                            sh """
                            zcat ../rootfs-built-${name}-${arch}.cpio.gz | cpio -iud
                            find . | cpio --format=newc --verbose --create --append --file=../rootfs-${arch}.cpio
                            """
                            }
                            sh "mv rootfs-${arch}.cpio rootfs-${name}-${arch}.cpio"
                            sh "gzip -c rootfs-${name}-${arch}.cpio > rootfs-${name}-${arch}.cpio.gz"
                        }
                        archiveArtifacts artifacts: "${pipeline_version}/${arch}/rootfs-${name}-${arch}.cpio.gz", fingerprint: true
                    }
                }
            }
        } finally {
            deleteDir()
        }
    }
}


def makeImageStep(String pipeline_version, String arch, String debian_arch, String debosFile, String extraPackages) {
    return {
        node {
            stage("Checkout") {
                checkout scm
            }

            docker.build("debian", "-f jenkins/debian/Dockerfile_debos --pull .").inside("--device=/dev/kvm ${getDockerArgs()}") {
                stage("Build base image for ${arch}") {
                    sh """
                        mkdir -p ${pipeline_version}/${arch}
                        debos -t architecture:${debian_arch} -t basename:${pipeline_version}/${arch} -t extra_packages:'${extraPackages}' ${debosFile}
                        mv ${pipeline_version}/${arch}/rootfs.cpio.gz ${pipeline_version}/${arch}/rootfs-${arch}.cpio.gz
                    """
                stash includes: "${pipeline_version}/${arch}/rootfs-${arch}.cpio.gz", name: "rootfs-${arch}"
                archiveArtifacts artifacts: "${pipeline_version}/${arch}/rootfs-${arch}.cpio.gz", fingerprint: true
                }
            }
        }
    }
}

def makeBuildStep(String pipeline_version, String arch, String debian_arch, String toolchain_arch, String name, String buildTestScript) {
    return {
        node {
            stage("Checkout") {
                checkout scm
            }

            docker.build("debian", "-f jenkins/debian/Dockerfile_debian --pull --build-arg=\"DEBIAN_ARCH=${debian_arch}\" .").inside("--device=/dev/kvm") {
                withEnv(["PIPELINE_VERSION=${pipeline_version}", "ARCH=${arch}", "TOOLCHAIN_ARCH=${toolchain_arch}", "NAME=${name}"]) {
                    sh buildTestScript
                }
                stash includes: "${pipeline_version}/${arch}/rootfs-built-${name}-${arch}.cpio.gz", name: "built-test-${arch}"
                archiveArtifacts artifacts: "${pipeline_version}/${arch}/rootfs-built-${name}-${arch}.cpio.gz", fingerprint: true
            }
        }
    }
}
