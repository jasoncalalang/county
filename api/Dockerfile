# Build context: the repository root.  docker build -f api/Dockerfile .
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
WORKDIR /src/api
COPY api/gradlew ./
COPY api/gradle gradle
# Fetches the Gradle distribution in a layer of its own, so a source change does not download it again.
RUN ./gradlew --no-daemon --version
COPY api/settings.gradle api/build.gradle api/gradle.properties ./
COPY api/src src
RUN ./gradlew --no-daemon quarkusBuild -x test

# Pinned by tag only: the digest could not be resolved when this was written. The CI job prints it
# (see .github/workflows/deploy.yml) so it can be pinned in a follow-up.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.23
ENV LANGUAGE='en_US:en'
# Four layers, so a code change does not re-copy the libraries.
COPY --from=build --chown=185 /src/api/build/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /src/api/build/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /src/api/build/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /src/api/build/quarkus-app/quarkus/ /deployments/quarkus/
EXPOSE 8080
# Only /tmp needs to be writable (an emptyDir).
USER 185
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    JAVA_APP_JAR="/deployments/quarkus-run.jar"
ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]
