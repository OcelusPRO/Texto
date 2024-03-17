FROM amazoncorretto:21-alpine3.19

ADD ./build/libs/app.jar /opt/program/
ADD ./src/main/resources/application.conf /app/application.conf
WORKDIR /app

EXPOSE 8080

ENTRYPOINT [ "java", "-jar", "/opt/program/app.jar", "-config=application.conf"]
