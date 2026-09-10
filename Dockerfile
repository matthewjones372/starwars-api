FROM eclipse-temurin:25-jdk AS build

ARG SBT_VERSION=1.11.7

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar xz -C /opt

ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /src

COPY project/build.properties project/plugins.sbt ./project/
COPY project/*.scala ./project/
COPY build.sbt ./
RUN sbt -batch update

COPY . .
RUN sbt -batch stage && mv target/out/jvm/*/swapi/universal/stage /opt/swapi

FROM eclipse-temurin:25-jre

WORKDIR /opt/swapi
COPY --from=build /opt/swapi ./

ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

ENTRYPOINT ["/opt/swapi/bin/swapi"]
