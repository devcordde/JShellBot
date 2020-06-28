FROM maven:3-jdk-10 as builder
WORKDIR /usr/app
COPY . .
RUN mvn package

FROM openjdk:10
WORKDIR /usr/app
COPY --from=builder /usr/app/target/JShellBot.jar JShellBot.jar
ENTRYPOINT java -jar JShellBot.jar