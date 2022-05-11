package io.ksqldb.tutorial;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;

import com.sendgrid.SendGrid;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.Method;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Content;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.Properties;
import java.util.Locale;
import java.io.IOException;

public class EmailSender {

    // Matches the broker port specified in the Docker Compose file.
    private final static String BOOTSTRAP_SERVERS = "localhost:29092";
    // Matches the Schema Registry port specified in the Docker Compose file.
    private final static String SCHEMA_REGISTRY_URL = "http://localhost:8081";
    // Matches the topic name specified in the ksqlDB CREATE TABLE statement.
    private final static String TOPIC = "possible_anomalies";
    // For you to fill in: which address SendGrid should send from.
    private final static String FROM_EMAIL = "<<INSERT SENDER HERE>>";
    // For you to fill in: the SendGrid API key to use their service.
    private final static String SENDGRID_API_KEY = "<<INSERT API KEY HERE>>";

    private final static SendGrid sg = new SendGrid(SENDGRID_API_KEY);
    private final static DateTimeFormatter formatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .withLocale(Locale.US)
                    .withZone(ZoneId.systemDefault());

    public static void main(final String[] args) throws IOException {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "email-sender");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        try (final KafkaConsumer<String, PossibleAnomaly> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));

            while (true) {
                final ConsumerRecords<String, PossibleAnomaly> records = consumer.poll(Duration.ofMillis(100));
                for (final ConsumerRecord<String, PossibleAnomaly> record : records) {
                    final PossibleAnomaly value = record.value();

                    if (value != null) {
                        sendEmail(value);
                    }
                }
            }
        }
    }

    private static void sendEmail(PossibleAnomaly anomaly) throws IOException {
        Email from = new Email(FROM_EMAIL);
        Email to = new Email(anomaly.getEmailAddress().toString());
        String subject = makeSubject(anomaly);
        Content content = new Content("text/plain", makeContent(anomaly));
        System.out.println(content.getValue());
        Mail mail = new Mail(from, subject, to, content);

        Request request = new Request();
        /**
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            System.out.println("Attempted to send email!\n");
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("Body: " + response.getBody());
            System.out.println("Headers: " + response.getHeaders());
            System.out.println("======================");
        } catch (IOException ex) {
            throw ex;
        }**/
    }

    private static String makeSubject(PossibleAnomaly anomaly) {
        return "Suspicious activity detected for card " + anomaly.getCardNumber();
    }

    private static String makeContent(PossibleAnomaly anomaly) {
        return String.format("Found suspicious activity for card number %s. %s transactions were made for a total of %s between %s and %s",
                anomaly.getCardNumber(),
                anomaly.getNAttempts(),
                anomaly.getTotalAmount(),
                formatter.format(Instant.ofEpochMilli(anomaly.getStartBoundary())),
                formatter.format(Instant.ofEpochMilli(anomaly.getEndBoundary())));
    }

}
