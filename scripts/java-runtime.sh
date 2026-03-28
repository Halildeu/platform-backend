# Shared Java runtime selection for backend scripts and Maven wrappers.

java_runtime_log() {
  if [ "${JAVA_RUNTIME_LOG:-1}" = "1" ]; then
    printf '%s\n' "$1" >&2
  fi
}

java_runtime_major_from_java() {
  java_cmd="$1"
  version_line=$("$java_cmd" -version 2>&1 | awk 'NR==1 { print $0 }')
  version=$(printf '%s\n' "$version_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')
  [ -n "$version" ] || return 1

  case "$version" in
    1.*) printf '%s\n' "$(printf '%s' "$version" | cut -d. -f2)" ;;
    *) printf '%s\n' "$(printf '%s' "$version" | sed 's/[.+-].*$//' | cut -d. -f1)" ;;
  esac
}

java_runtime_home_matches_target() {
  home="$1"
  target="$2"

  [ -x "$home/bin/java" ] || return 1
  [ -x "$home/bin/javac" ] || return 1

  detected_major=$(java_runtime_major_from_java "$home/bin/java" 2>/dev/null || true)
  [ "$detected_major" = "$target" ]
}

java_runtime_find_home_in_prefix() {
  prefix="$1"
  target="$2"

  [ -n "${prefix:-}" ] || return 1

  for candidate_home in \
    "$prefix" \
    "$prefix/Contents/Home" \
    "$prefix/Home" \
    "$prefix/libexec/openjdk.jdk/Contents/Home"
  do
    if java_runtime_home_matches_target "$candidate_home" "$target"; then
      printf '%s\n' "$candidate_home"
      return 0
    fi
  done

  return 1
}

java_runtime_find_target_home() {
  target="$1"

  eval "candidate_home=\${JAVA${target}_HOME-}"
  if [ -n "${candidate_home:-}" ] && java_runtime_home_matches_target "$candidate_home" "$target"; then
    printf '%s\n' "$candidate_home"
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate_home=$(/usr/libexec/java_home -v "$target" 2>/dev/null || true)
    if [ -n "${candidate_home:-}" ] && java_runtime_home_matches_target "$candidate_home" "$target"; then
      printf '%s\n' "$candidate_home"
      return 0
    fi
  fi

  for prefix in \
    "/opt/homebrew/opt/openjdk@${target}" \
    "/usr/local/opt/openjdk@${target}" \
    "/opt/homebrew/opt/temurin@${target}" \
    "/usr/local/opt/temurin@${target}" \
    "/opt/homebrew/opt/zulu@${target}" \
    "/usr/local/opt/zulu@${target}" \
    "/opt/homebrew/opt/corretto@${target}" \
    "/usr/local/opt/corretto@${target}"
  do
    candidate_home=$(java_runtime_find_home_in_prefix "$prefix" "$target" 2>/dev/null || true)
    if [ -n "${candidate_home:-}" ]; then
      printf '%s\n' "$candidate_home"
      return 0
    fi
  done

  if command -v brew >/dev/null 2>&1; then
    for formula in "openjdk@${target}" "temurin@${target}" "zulu@${target}" "corretto@${target}"; do
      brew_prefix=$(brew --prefix "$formula" 2>/dev/null || true)
      candidate_home=$(java_runtime_find_home_in_prefix "$brew_prefix" "$target" 2>/dev/null || true)
      if [ -n "${candidate_home:-}" ]; then
        printf '%s\n' "$candidate_home"
        return 0
      fi
    done
  fi

  return 1
}

java_runtime_use_home() {
  home="$1"
  export JAVA_HOME="$home"
  case ":$PATH:" in
    *":$home/bin:"*) ;;
    *) export PATH="$home/bin:$PATH" ;;
  esac
}

java_runtime_append_maven_opt() {
  flag="$1"
  case " ${MAVEN_OPTS:-} " in
    *" ${flag} "*) ;;
    *) export MAVEN_OPTS="${MAVEN_OPTS:+${MAVEN_OPTS} }${flag}" ;;
  esac
}

java_runtime_prepare_maven_opts() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    current_java="${JAVA_HOME}/bin/java"
  else
    current_java=$(command -v java 2>/dev/null || true)
  fi

  [ -n "${current_java:-}" ] || return 0
  current_major=$(java_runtime_major_from_java "$current_java" 2>/dev/null || true)
  [ -n "${current_major:-}" ] || return 0

  if [ "$current_major" -ge 24 ] 2>/dev/null; then
    java_runtime_append_maven_opt "--enable-native-access=ALL-UNNAMED"
  fi
}

codex_maven_sanitize_token() {
  raw="${1:-}"
  raw=$(printf '%s' "$raw" | sed 's/[^A-Za-z0-9._-]/-/g')
  raw=$(printf '%s' "$raw" | sed 's/^-*//; s/-*$//')
  if [ -z "$raw" ]; then
    raw="run"
  fi
  printf '%s\n' "$raw"
}

codex_maven_arg_triggers_test_phase() {
  arg="${1:-}"
  case "$arg" in
    test | verify | install | package | deploy | integration-test)
      return 0
      ;;
    surefire:test | surefire:test-only | failsafe:integration-test | failsafe:verify)
      return 0
      ;;
  esac
  return 1
}

codex_prepare_maven_wrapper_args() {
  if [ "${CODEX_SUREFIRE_ISOLATION_DISABLE:-0}" = "1" ]; then
    return 0
  fi

  context="${1:-mvnw}"
  shift || true

  unset CODEX_MAVEN_WRAPPER_TEMPDIR CODEX_MAVEN_WRAPPER_REPORT_SUFFIX || true

  trigger_tests="0"
  tests_disabled="0"
  has_temp_dir="0"
  has_report_suffix="0"

  for arg in "$@"; do
    case "$arg" in
      -DskipTests | -DskipTests=true | -Dmaven.test.skip=true)
        tests_disabled="1"
        ;;
      -DtempDir=*)
        has_temp_dir="1"
        ;;
      -Dsurefire.reportNameSuffix=*)
        has_report_suffix="1"
        ;;
      *)
        if codex_maven_arg_triggers_test_phase "$arg"; then
          trigger_tests="1"
        fi
        ;;
    esac
  done

  if [ "$trigger_tests" != "1" ] || [ "$tests_disabled" = "1" ]; then
    return 0
  fi

  timestamp=$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || printf '%s' "unknown-time")
  safe_context=$(codex_maven_sanitize_token "$context")
  run_id=$(codex_maven_sanitize_token "${CODEX_SUREFIRE_RUN_ID:-${safe_context}-${timestamp}-$$}")

  if [ "$has_temp_dir" = "0" ]; then
    CODEX_MAVEN_WRAPPER_TEMPDIR="-DtempDir=surefire-${run_id}"
    export CODEX_MAVEN_WRAPPER_TEMPDIR
  fi

  if [ "$has_report_suffix" = "0" ]; then
    CODEX_MAVEN_WRAPPER_REPORT_SUFFIX="-Dsurefire.reportNameSuffix=${run_id}"
    export CODEX_MAVEN_WRAPPER_REPORT_SUFFIX
  fi

  if [ -n "${CODEX_MAVEN_WRAPPER_TEMPDIR:-}" ] || [ -n "${CODEX_MAVEN_WRAPPER_REPORT_SUFFIX:-}" ]; then
    java_runtime_log "[mvnw] ${context}: surefire izolasyonu aktif${CODEX_MAVEN_WRAPPER_TEMPDIR:+ ${CODEX_MAVEN_WRAPPER_TEMPDIR}}${CODEX_MAVEN_WRAPPER_REPORT_SUFFIX:+ ${CODEX_MAVEN_WRAPPER_REPORT_SUFFIX}}"
  fi
}

codex_select_java_runtime() {
  if [ "${JAVA_RUNTIME_DISABLE:-0}" = "1" ]; then
    return 0
  fi

  if [ "${CODEX_JAVA_RUNTIME_SELECTED:-0}" = "1" ]; then
    return 0
  fi

  target="${1:-${JAVA_RUNTIME_TARGET:-21}}"
  context="${2:-java-runtime}"
  target_home=$(java_runtime_find_target_home "$target" 2>/dev/null || true)

  if [ -n "${target_home:-}" ]; then
    java_runtime_use_home "$target_home"
    java_runtime_log "[java-runtime] ${context}: JDK ${target} seçildi (${JAVA_HOME})"
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    current_major=$(java_runtime_major_from_java "${JAVA_HOME}/bin/java" 2>/dev/null || true)
    java_runtime_log "[java-runtime] ${context}: JDK ${target} bulunamadı, mevcut JAVA_HOME kullanılacak${current_major:+ (JDK ${current_major})}: ${JAVA_HOME}"
  else
    path_java=$(command -v java 2>/dev/null || true)
    if [ -n "${path_java:-}" ]; then
      current_major=$(java_runtime_major_from_java "$path_java" 2>/dev/null || true)
      java_runtime_log "[java-runtime] ${context}: JDK ${target} bulunamadı, PATH üzerindeki java kullanılacak${current_major:+ (JDK ${current_major})}: ${path_java}"
    else
      java_runtime_log "[java-runtime] ${context}: UYARI JDK ${target} bulunamadı ve PATH üzerinde java yok."
    fi
  fi

  java_runtime_prepare_maven_opts
  export CODEX_JAVA_RUNTIME_SELECTED=1
  return 0
}
