FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./lib

ENV MINISQL_DATABASE_TYPE=H2
ENV MINISQL_JDBC_USER=sa
ENV MINISQL_JDBC_PASSWORD=

CMD ["java", "-cp", "classes:lib/*", "minisql.datanode.DataNodeServer"]
