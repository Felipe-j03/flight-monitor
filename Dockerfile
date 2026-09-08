# Two stages: the fat JRE toolchain never reaches the runtime image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Never run as root.
RUN groupadd --system monitor && useradd --system --gid monitor --create-home monitor
USER monitor

COPY --from=build --chown=monitor:monitor /build/target/flight-monitor-*.jar app.jar

EXPOSE 8080

# Container-aware heap sizing; the monitor is I/O bound and needs very little memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

HEALTHCHECK --interval=60s --timeout=5s --start-period=45s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
