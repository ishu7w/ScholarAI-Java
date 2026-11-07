FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 scholarai && adduser -D -u 10001 -G scholarai scholarai
WORKDIR /app
RUN mkdir -p /app/data && chown -R scholarai:scholarai /app
COPY --from=build --chown=scholarai:scholarai /build/target/scholarai-1.0.0.jar /app/scholarai.jar
USER scholarai
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:${PORT}/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/scholarai.jar"]
