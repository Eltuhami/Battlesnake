# Use an official Maven image to build the application
FROM maven:3.8-jdk-8 AS build
WORKDIR /app

# Copy the project files
COPY pom.xml .
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Use a lightweight JRE image to run the application
FROM openjdk:8-jre-slim
WORKDIR /app

# Copy the built jar file from the build stage
COPY --from=build /app/target/starter-snake-java.jar /app/starter-snake-java.jar

# Expose the default port
EXPOSE 8080

# Command to run the application
# We use shell form to pass the PORT environment variable as a system property
CMD ["sh", "-c", "java -DPORT=${PORT:-8080} -jar starter-snake-java.jar"]
