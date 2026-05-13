# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -DskipTests package

# --- runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/aetheros-control/target/aetheros-control-*.jar /app/aetheros.jar
EXPOSE 8080 1080
ENTRYPOINT ["java","--enable-preview","-jar","/app/aetheros.jar"]
