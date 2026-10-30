# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy maven wrapper and pom
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy source
COPY src/ src/

# Build the application
RUN ./mvnw package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS production
# Business timezone, as in the other services; override per deployment with TZ.
ENV TZ=Africa/Lagos
WORKDIR /app

# Fixed non-root ids, so the deployment can grant the journal volume to them.
RUN addgroup -S -g 1001 appgroup && adduser -S -u 1001 -G appgroup appuser

# Copy the built artifact
COPY --from=build /app/target/quarkus-app/lib/ /app/lib/
COPY --from=build /app/target/quarkus-app/*.jar /app/
COPY --from=build /app/target/quarkus-app/app/ /app/app/
COPY --from=build /app/target/quarkus-app/quarkus/ /app/quarkus/

# Create directories for logs, keys and the adapter journal
RUN mkdir -p /app/logs /etc/nps/keys /var/lib/nps/journal && \
    chown -R appuser:appgroup /app /etc/nps /var/lib/nps

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/quarkus-run.jar"]
