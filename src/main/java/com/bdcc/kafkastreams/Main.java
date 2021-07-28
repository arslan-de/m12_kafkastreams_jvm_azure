package com.bdcc.kafkastreams;

import org.apache.avro.generic.GenericRecord;

import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bdcc.kafkastreams.entities.ExpediaCategorized;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class Main {
    static final ObjectMapper om = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "kafka-streams");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.confluent.svc.cluster.local:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100 * 1000);

        final String INPUT_TOPIC_NAME = "expedia";
        final String OUTPUT_TOPIC_NAME = "expedia-ext";

        GenericAvroSerde expediaValueSerde = new GenericAvroSerde();
        expediaValueSerde.configure(
                Map.of("schema.registry.url", "http://localhost:8081"), false
        );

        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, GenericRecord> expediaStream = builder.stream(INPUT_TOPIC_NAME,
                Consumed.with(Serdes.String(), expediaValueSerde));

        // map the expedia data to the ExpediaCategorized objects
        KStream<String, ExpediaCategorized> stringExpediaCategorizedKStream = expediaStream.mapValues(record -> {
            String checkInDate = Optional.ofNullable(record.get("srch_ci")).orElse("").toString();
            String checkOutDate = Optional.ofNullable(record.get("srch_co")).orElse("").toString();

            String category = getCategoryByPeriod(checkInDate, checkOutDate);

            return new ExpediaCategorized(
                    (Long) record.get("id"),
                    (Long) record.get("hotel_id"),
                    category);

        });


        Serde<ExpediaCategorized> expediaCategorizedSerde = new SpecificAvroSerde<>();
        expediaCategorizedSerde.configure(Map.of("schema.registry.url", "http://localhost:8081"), false);
        stringExpediaCategorizedKStream.to(OUTPUT_TOPIC_NAME, Produced.with(Serdes.String(), expediaCategorizedSerde));

        final Topology topology = builder.build();
        System.out.println(topology.describe());

        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        // attach shutdown handler to catch control-c
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }

    static String getCategoryByPeriod(String checkIn, String checkOut) {
        String ERRONEOUS_DATE = "Erroneous data";
        String SHORT_STAY = "Short stay";
        String STANDARD_STAY = "Standard stay";
        String STANDARD_EXTENDED_STAY = "Standard extended stay";
        String LONG_STAY = "Long stay";

        long stayPeriod = calculatePeriod(checkIn, checkOut);

        if (stayPeriod >= 1 && stayPeriod <= 4) {
            return SHORT_STAY;
        } else if (stayPeriod >= 5 && stayPeriod <= 10) {
            return STANDARD_STAY;
        } else if (stayPeriod >= 11 && stayPeriod <= 14) {
            return STANDARD_EXTENDED_STAY;
        } else if (stayPeriod > 14) {
            return LONG_STAY;
        }
        return ERRONEOUS_DATE;
    }

    private static long calculatePeriod(String checkIn, String checkOut) {
        try {
            LocalDate checkInDate = LocalDate.parse(checkIn);
            LocalDate checkOutDate = LocalDate.parse(checkOut);
            return ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        } catch (Exception ignored) {
        }
        return 0;
    }

}
