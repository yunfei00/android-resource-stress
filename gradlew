#!/bin/sh

# Minimal POSIX launcher for the Gradle wrapper. The wrapper JAR and distribution
# version are pinned under gradle/wrapper.

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if [ ! -x "$JAVACMD" ]; then
    echo "ERROR: Java executable was not found: $JAVACMD" >&2
    exit 1
fi

exec "$JAVACMD" \
    -Dorg.gradle.appname=gradlew \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
