# lab15-ksqldb-credittransactions

This tutorial is based on the ksqlDb tutorial for event-driven microservices from [here](https://docs.ksqldb.io/en/latest/tutorials/event-driven-microservice/?_ga=2.238044992.1187389338.1650009400-892569557.1649877876).

Imagine that you work at a financial services company which clears many credit card transactions each day. You want to prevent malicious activity in your customer base. When a high number of transactions occurs in a narrow window of time, you want to notify the cardholder of suspicious activity.

This tutorial shows how to create an event-driven microservice that identifies suspicious activity and notifies customers. It demonstrates finding anomalies with ksqlDB and sending alert emails using a simple Kafka consumer with SendGrid.

### Start the Stack

You can start the local Kafka cluster including ksqlDB using the following command:

```sh
docker-compose up
```

### Create the transactions stream

Connect to ksqlDB's server by using its interactive CLI. Run the following command from your host:

```sh
docker exec -it ksqldb-cli ksql http://ksqldb-server:8088
```

Before you issue more commands, tell ksqlDB to start all queries from the earliest point in each topic:

```sh
SET 'auto.offset.reset' = 'earliest';
```

We want to model a stream of credit card transactions from which we'll look for anomalous activity. To do that, create a ksqlDB stream to represent the transactions. Each transaction has a few key pieces of information, like the card number, amount, and email address that it's associated with. Because the specified topic (transactions) does not exist yet, ksqlDB creates it on your behalf.

```roomsql
CREATE STREAM transactions (
    tx_id VARCHAR KEY,
    email_address VARCHAR,
    card_number VARCHAR,
    timestamp VARCHAR,
    amount DECIMAL(12, 2)
) WITH (
    kafka_topic = 'transactions',
    partitions = 8,
    value_format = 'avro',
    timestamp = 'timestamp',
    timestamp_format = 'yyyy-MM-dd''T''HH:mm:ss'
);
```
Notice that this stream is configured with a custom *timestamp* to signal that event-time should be used instead of processing-time. What this means is that when ksqlDB does time-related operations over the stream, it uses the timestamp column to measure time, not the current time of the operating system. This makes it possible to handle out-of-order events.

The stream is also configured to use the *Avro* format for the value part of the underlying Kafka records that it generates. Because ksqlDB has been configured with Schema Registry (as part of the Docker Compose file), the schemas of each stream and table are centrally tracked. We'll make use of this in our microservice later.

### Seed some transaction events

With the stream in place, seed it with some initial events. Run these statements at the CLI:

```roomsql
INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'michael@example.com',
    '358579699410099',
    'f88c5ebb-699c-4a7b-b544-45b30681cc39',
    '2020-04-22T03:19:58',
    50.25
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'derek@example.com',
    '352642227248344',
    '0cf100ca-993c-427f-9ea5-e892ef350363',
    '2020-04-22T12:50:30',
    18.97
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'colin@example.com',
    '373913272311617',
    'de9831c0-7cf1-4ebf-881d-0415edec0d6b',
    '2020-04-22T09:45:15',
    12.50
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'michael@example.com',
    '358579699410099',
    '044530c0-b15d-4648-8f05-940acc321eb7',
    '2020-04-22T03:19:54',
    103.43
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'derek@example.com',
    '352642227248344',
    '5d916e65-1af3-4142-9fd3-302dd55c512f',
    '2020-04-22T12:50:25',
    3200.80
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'derek@example.com',
    '352642227248344',
    'd7d47fdb-75e9-46c0-93f6-d42ff1432eea',
    '2020-04-22T12:51:55',
    154.32
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'michael@example.com',
    '358579699410099',
    'c5719d20-8d4a-47d4-8cd3-52ed784c89dc',
    '2020-04-22T03:19:32',
    78.73
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'colin@example.com',
    '373913272311617',
    '2360d53e-3fad-4e9a-b306-b166b7ca4f64',
    '2020-04-22T09:45:35',
    234.65
);

INSERT INTO transactions (
    email_address, card_number, tx_id, timestamp, amount
) VALUES (
    'colin@example.com',
    '373913272311617',
    'de9831c0-7cf1-4ebf-881d-0415edec0d6b',
    '2020-04-22T09:44:03',
    150.00
);
```

### Create the anomalies table

If a single credit card is transacted many times within a short duration, there's probably something suspicious going on. A table is an ideal choice to model this because you want to aggregate events over time and find activity that spans multiple events. Run the following statement:

```roomsql
CREATE TABLE possible_anomalies WITH (
    kafka_topic = 'possible_anomalies',
    VALUE_AVRO_SCHEMA_FULL_NAME = 'io.ksqldb.tutorial.PossibleAnomaly'
)   AS
    SELECT card_number AS `card_number_key`,
           as_value(card_number) AS `card_number`,
           latest_by_offset(email_address) AS `email_address`,
           count(*) AS `n_attempts`,
           sum(amount) AS `total_amount`,
           collect_list(tx_id) AS `tx_ids`,
           WINDOWSTART as `start_boundary`,
           WINDOWEND as `end_boundary`
    FROM transactions
    WINDOW TUMBLING (SIZE 30 SECONDS, RETENTION 1000 DAYS)
    GROUP BY card_number
    HAVING count(*) >= 3
    EMIT CHANGES;
```

Here's what this statement does:

* For each credit card number, 30 second tumbling windows are created to group activity. A new row is inserted into the table when at least 3 transactions take place inside a given window.
* The window retains data for the last 1000 days based on each row's timestamp. In general, you should choose your retention carefully. It is a trade-off between storing data longer and having larger state sizes. The very long retention period used in this tutorial is useful because the timestamps are fixed at the time of writing this and won't need to be adjusted often to account for retention.
* The credit card number is selected twice. In the first instance, it becomes part of the underlying Kafka record key, because it's present in the `group by clause`, which is used for sharding. In the second instance, the `as_value` function is used to make it available in the value, too. This is generally for convenience.
* The individual transaction IDs and amounts that make up the window are collected as lists.
* The last transaction's email address is "carried forward" with `latest_by_offset`.
* Column aliases are surrounded by backticks, which tells ksqlDB to use exactly that case. ksqlDB uppercases identity names by default.
*The underlying Kafka topic for this table is explicitly set to `possible_anomalies`.
* The Avro schema that ksqlDB generates for the value portion of its records is recorded under the namespace `io.ksqldb.tutorial.PossibleAnomaly`. You'll use this later in the microservice.

Check what anomalies the table picked up. Run the following statement to select a stream of events emitted from the table:

```roomsql
SELECT * FROM possible_anomalies EMIT CHANGES;
```

This should yield a single row. Three transactions for card number 358579699410099 were recorded with timestamps within a single 30-second tumbling window:

````shell
+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+
|card_number_key     |WINDOWSTART         |WINDOWEND           |card_number         |email_address       |n_attempts          |total_amount        |tx_ids              |start_boundary      |end_boundary        |
+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+
|358579699410099     |1587525570000       |1587525600000       |358579699410099     |michael@example.com |3                   |232.41              |[f88c5ebb-699c-4a7b-|1587525570000       |1587525600000       |
|                    |                    |                    |                    |                    |                    |                    |b544-45b30681cc39, c|                    |                    |
|                    |                    |                    |                    |                    |                    |                    |5719d20-8d4a-47d4-8c|                    |                    |
|                    |                    |                    |                    |                    |                    |                    |d3-52ed784c89dc, 044|                    |                    |
|                    |                    |                    |                    |                    |                    |                    |530c0-b15d-4648-8f05|                    |                    |
|                    |                    |                    |                    |                    |                    |                    |-940acc321eb7]      |                    |                    |

````

You can also print out the contents of the underlying Kafka topic for this table, which you will programmatically access in the microservice:

````shell
PRINT 'possible_anomalies' FROM BEGINNING;
````

### The Kafka client project

Notice that so far, all the heavy lifting happens inside of ksqlDB. ksqlDB takes care of the stateful stream processing. Triggering side-effects will be delegated to a light-weight service that consumes from a Kafka topic. You want to send an email each time an anomaly is found. To do that, you'll implement a simple, scalable microservice. In practice, you might use Kafka Streams to handle this piece, but to keep things simple, just use a Kafka consumer client.

#### Download and compile the Avro schemas
Before you can begin coding your microservice, you'll need access to the Avro schemas that the Kafka topic is serialized with. Confluent has a Maven plugin that makes this simple, which you might have already noticed is present in the pom.xml file. Run the following command, which downloads the required Avro schema out of Schema Registry to your local machine:

````shell
mvn schema-registry:download
````

You should now have a file called `src/main/avro/possible_anomalies-value.avsc`. This is the Avro schema generated by ksqlDB for the value portion of the Kafka records of the `possible_anomalies` topic.

Next, compile the Avro schema into a Java file. The Avro Maven plugin (already added to the pom.xml file, too) makes this simple:

````shell
mvn generate-sources
````

You should now have a file called `target/generated-sources/io/ksqldb/tutorial/PossibleAnomaly.java` containing the compiled Java code.

#### The Kafka consumer code

The provided [EMailSender](/src/main/java/io/ksqldb/tutorial/EmailSender.java) class is a simple program that consumes events from Kafka and sends an email with SendGrid for each one it finds. There are a few constants to fill in, including a SendGrid API key. You can get one by signing up for SendGrid.

Run the program from your IDE or via:

````shell
mvn exec:java
````

If everything is configured correctly, you will see a print on the console and emails will be sent whenever an anomaly is detected. There are a few things to note with this simple implementation.

First, if you start more instances of this microservice, the partitions of the `possible_anomalies` topic will be load balanced across them. This takes advantage of the standard Kafka consumer groups behavior.

Second, this microservice is configured to checkpoint its progress every 100 milliseconds through the `ENABLE_AUTO_COMMIT_CONFIG` configuration. That means any successfully processed messages will not be reprocessed if the microservice is taken down and turned on again.

Finally, note that ksqlDB emits a new event every time a tumbling window changes. ksqlDB uses a model called "refinements" to continually emit new changes to stateful aggregations. For example, if an anomaly was detected because three credit card transactions were found in a given interval, an event would be emitted from the table. If a fourth is detected in the same interval, another event is emitted. Because SendGrid does not (at the time of writing) support idempotent email submission, you would need to have a small piece of logic in your program to prevent sending an email multiple times for the same period. This is omitted for brevity.

If you wish, you can continue the example by inserting more events into the `transactions` topics.