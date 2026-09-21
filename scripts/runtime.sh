#!/bin/bash
# Shared source/app runtime selection. No Python, network or GUI dependencies.
ilo_runtime_error() { printf 'Java 8 runtime error: %s\n' "$*" >&2; return 1; }

ilo_validate_arch() {
    local executable="$1" arches
    if [ "$(uname -s)" = Darwin ]; then
        arches="$(/usr/bin/lipo -archs "$executable" 2>/dev/null)" || {
            ilo_runtime_error "cannot verify native architecture: $executable"; return 1;
        }
        case " $arches " in
            *" $ILO_HOST_ARCH "*) ;;
            *) ilo_runtime_error "$executable architecture ($arches) does not support $ILO_HOST_ARCH"; return 1 ;;
        esac
    fi
}

ilo_validate_runtime() {
    local root="$1" need_compiler="$2" properties compiler_version
    [ ! -d "$root/Contents/Home" ] || root="$root/Contents/Home"
    [ -n "$root" ] && [ -x "$root/bin/java" ] || {
        ilo_runtime_error "JDK8 must name a Java 8 home with executable bin/java: $root"; return 1;
    }
    ILO_JAVA_HOME="$(cd "$root" && pwd -P)" || return 1
    ILO_JAVA="$ILO_JAVA_HOME/bin/java"
    ILO_JAVAC="$ILO_JAVA_HOME/bin/javac"
    ILO_JAVA_VERSION="$("$ILO_JAVA" -version 2>&1)" || {
        ilo_runtime_error "cannot run $ILO_JAVA"; return 1;
    }
    case "$ILO_JAVA_VERSION" in
        *'version "1.8.'*) ;;
        *) ilo_runtime_error "JDK8 has unsupported java version (expected Java 8): $ILO_JAVA_VERSION"; return 1 ;;
    esac
    ilo_validate_arch "$ILO_JAVA" || return 1
    properties="$("$ILO_JAVA" -XshowSettings:properties -version 2>&1)" || {
        ilo_runtime_error "cannot query architecture: $ILO_JAVA"; return 1;
    }
    if [[ "$properties" =~ os\.arch[[:space:]]*=[[:space:]]*([^[:space:]]+) ]]; then
        ILO_RUNTIME_ARCH="${BASH_REMATCH[1]}"
    else
        ilo_runtime_error "java did not report os.arch: $ILO_JAVA"; return 1
    fi
    case "$ILO_HOST_ARCH:$ILO_RUNTIME_ARCH" in
        arm64:arm64|arm64:aarch64|aarch64:aarch64|x86_64:x86_64|x86_64:amd64) ;;
        *) ilo_runtime_error "running Java architecture $ILO_RUNTIME_ARCH does not match host $ILO_HOST_ARCH"; return 1 ;;
    esac
    if [ "$need_compiler" = yes ]; then
        [ -x "$ILO_JAVAC" ] || { ilo_runtime_error "JDK8 needs javac for source build: $ILO_JAVAC"; return 1; }
        compiler_version="$("$ILO_JAVAC" -version 2>&1)" || {
            ilo_runtime_error "cannot run javac: $ILO_JAVAC"; return 1;
        }
        case "$compiler_version" in
            'javac 1.8.'*) ;;
            *) ilo_runtime_error "JDK8 needs javac version 1.8, found $compiler_version"; return 1 ;;
        esac
        ilo_validate_arch "$ILO_JAVAC" || return 1
    fi
}

ilo_find_runtime() {
    local need_compiler="${1:-no}" candidate
    ILO_HOST_ARCH="$(uname -m)"
    # uname reports x86_64 under Rosetta; check hardware, not this shell's ISA.
    if [ "$(uname -s)" = Darwin ] && [ "$(/usr/sbin/sysctl -n hw.optional.arm64 2>/dev/null || true)" = 1 ]; then
        ILO_HOST_ARCH=arm64
    fi
    if [ "${JDK8+x}" = x ]; then
        ilo_validate_runtime "$JDK8" "$need_compiler"
        return $?
    fi
    candidate=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
    if [ -d "$candidate" ] && ilo_validate_runtime "$candidate" "$need_compiler"; then
        return 0
    fi
    if [ -x /usr/libexec/java_home ]; then
        candidate="$(/usr/libexec/java_home -F -v 1.8 -a "$ILO_HOST_ARCH" 2>/dev/null)" || candidate=""
        if [ -n "$candidate" ] && ilo_validate_runtime "$candidate" "$need_compiler"; then
            return 0
        fi
    fi
    ilo_runtime_error "no compatible $ILO_HOST_ARCH Java 8 found; set JDK8 to a Java 8 home (source builds require a JDK with bin/javac)."
}

ilo_print_runtime() {
    printf 'Java home: %s\nArchitecture: %s (host %s)\n%s\n' "$ILO_JAVA_HOME" "$ILO_RUNTIME_ARCH" "$ILO_HOST_ARCH" "$ILO_JAVA_VERSION"
}
