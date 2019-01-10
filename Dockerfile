FROM maven:3-jdk-8-slim
ADD . /code
WORKDIR /code
RUN mvn package

FROM scratch
COPY --from=0 /code/target/in-toto.hpi /in-toto.hpi
