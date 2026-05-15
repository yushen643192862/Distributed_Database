FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
ENV MINISQL_DATA=/app/data/minisql-state.bin
COPY --from=build /app/target/classes ./classes

CMD ["java", "-cp", "classes", "edu.minisql.app.App"]
