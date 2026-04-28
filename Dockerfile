FROM eclipse-temurin:26-jdk AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B -q package -DskipTests

FROM eclipse-temurin:26-jre
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/target/karpathy-wiki-*.jar app.jar
COPY skills ./skills
COPY SCHEMA.md .
ENTRYPOINT ["java", "-jar", "app.jar"]
