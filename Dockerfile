FROM eclipse-temurin:21-jdk-alpine
VOLUME /tmp
ARG JAR_FILE=target/springapp-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8000
ENTRYPOINT ["java","-jar","/app.jar"]
# For Spring-Boot project, use the entrypoint below to reduce Tomcat startup time.
#ENTRYPOINT exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar
