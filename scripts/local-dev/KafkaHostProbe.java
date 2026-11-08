import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Proves a program on THIS PC (not in a container) can send to and read from
 * the Docker Kafka broker -- which is what a service started from the IDE does.
 *
 * Connecting to localhost:9092 is not enough on its own: the broker answers the
 * first connection with the address clients must use from then on (its
 * "advertised listener"). If that address is the container name `kafka`, this
 * PC cannot resolve it and every send times out. A bootstrap-only check (list
 * topics, a health endpoint) passes anyway, so this probe sends a real record
 * and reads it back.
 *
 * Run by scripts/local-dev-test.ps1, which supplies the classpath:
 *   java -cp <kafka-clients.jar;slf4j-api.jar> KafkaHostProbe.java [bootstrap]
 * Exit code 0 = the round trip worked.
 */
public class KafkaHostProbe {

    public static void main(String[] args) {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        String topic = "oriah.local-dev-probe";
        String value = "probe-" + UUID.randomUUID();

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "15000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "20000");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(topic, value)).get();
        } catch (Exception e) {
            System.out.println("FAIL: could not send to " + bootstrap + ": " + e);
            System.exit(1);
        }

        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "local-dev-probe-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofSeconds(2))) {
                    if (value.equals(r.value())) {
                        System.out.println("PASS: sent and read back one record via " + bootstrap);
                        System.exit(0);
                    }
                }
            }
        }
        System.out.println("FAIL: sent, but did not read the record back within 30s");
        System.exit(1);
    }
}
