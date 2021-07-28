FROM adoptopenjdk:11.0.8_10-jre-hotspot
COPY kafkastreams-1.0.0-jar-with-dependencies.jar /app/kafkastreams.jar
ENTRYPOINT exec java -jar /app/kafkastreams.jar $ARGS