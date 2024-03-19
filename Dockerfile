FROM amazoncorretto:21-alpine3.19

ADD ./build/libs/app.jar /opt/program/app.jar
ADD ./templates /opt/program/templates
ADD ./static /opt/program/static

WORKDIR /app

EXPOSE 8080

ENTRYPOINT [ "java", "-jar", "/opt/program/app.jar"]
