def call() {
  return VersionNumber(versionNumberString: '${BUILD_DATE_FORMATTED,"yyyyMMdd"}.${BUILDS_TODAY_Z}')
}

