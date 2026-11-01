FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./dependency
COPY --from=build /app/target/*.jar ./app.jar
ENV PORT=10000
EXPOSE 10000
CMD ["java","-cp","classes:dependency/*:app.jar","com.wilddeck.app.HumanWebServer"]
