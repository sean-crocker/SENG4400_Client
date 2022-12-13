package com.seng4400;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.google.gson.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

/**
 * Client class used to take the role of the consumer or subscriber. Every message the Client receives from the Server
 * contains a question with a value. The client calls a function to retrieve a list of primes numbers from one to the
 * value given along with the time taken to produce the list of prime numbers.
 *
 * The message containing both the prime numbers and the time taken is both printed to console and sent as a POST REST
 * call to the remote RPC endpoint which is incorporated with Google App Engine.
 *
 * @author  Sean Crocker
 * @version 1.0
 * @since   01/06/2022
 */
public class Client {

    /**
     * Function responsible for creating and setting up the consumer by applying configurations. Once the configurations
     * are set a new consumer is returned.
     *
     * @return          the consumer with properties set.
     */
    private static Consumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ass2");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "20000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /**
     * Driver function which can take an optional program argument to configure the URL. If more than one argument is
     * given, an illegal argument exception is thrown. If the URL given is not appropriate, a malformed url exception
     * is thrown. Once the argument is retrieved the main function can run.
     *
     * @param args              A value that can be used as an alternative URL
     * @throws IOException      Throws if URL is invalid
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 1)
            throw new IllegalArgumentException("Error. Program must run with a maximum of one optional argument.");
        String url = args.length == 1 ? args[0] : "https://australia-southeast1-seng4400-350016.cloudfunctions.net/endpoint-function-1";
        run(url);
    }

    /**
     * Main function responsible for subscribing to the queue, getting consumer records and for each record, obtain
     * the value so that a list of primes can be obtained along with the time taken. With both the time and prime
     * numbers, a JSON object is created to be posted to the endpoint with hardcoded credentials for authentication.
     *
     * The credentials are used to call a function to acquire a signed JSON web token. The web token, URL, and the JSON
     * object are used to call another function to make a post request.
     *
     * @param url       The URL to call the POST request
     */
    private static void run(String url) throws IOException {
        Consumer<String, String> consumer = createConsumer();            // Create the consumer
        consumer.subscribe(Collections.singletonList("seng4400"));
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.setPrettyPrinting().create();
        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, String> record : records) {
                if (Integer.parseInt(record.value()) > 1_000_000)
                    break;
                long startTime = System.nanoTime();                     // Time acquired before getting prime list
                ArrayList<Integer> list = getPrimes(Integer.parseInt(record.value()));
                long endTime = System.nanoTime();                       // Time acquired after getting prime list

                // Creating the JSON object with values obtained
                JsonObject jsonObj = new JsonObject();
                JsonArray array = gsonBuilder.create().toJsonTree(list).getAsJsonArray();
                JsonElement answer = gsonBuilder.create().toJsonTree(array);
                JsonElement time = gsonBuilder.create().toJsonTree((endTime-startTime)/1_000_000);
                jsonObj.add("answer", answer);
                jsonObj.add("time_taken", time);

                // Output to Console and send to remote rest-point
                System.out.println(gson.toJson(jsonObj));
                postRequest(url, jsonObj);
            }
        }
    }

    /**
     * The function makes an authenticated post request to the service URL by sending a request body consisting of the
     * prime numbers and time obtained. An identification token is created using an audience which is currently the name
     * of the default service account.
     *
     * If no URL was declared by the user, the default service URL will call a HTTP cloud function trigger to handle
     * the POST request.
     *
     * @param serviceUrl    The value of the URL used to call a service
     * @param jsonObj       The JSON object containing the prime numbers and time taken
     * @throws IOException  Throws exception if URL is invalid
     */
    private static void postRequest(String serviceUrl, JsonObject jsonObj) throws IOException {
        String audience = "App Engine default service account";
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (!(credentials instanceof IdTokenProvider)) {
            throw new IllegalArgumentException("Credentials are not an instance of IdTokenProvider.");
        }
        IdTokenCredentials tokenCredential =
                IdTokenCredentials.newBuilder()
                        .setIdTokenProvider((IdTokenProvider) credentials)
                        .setTargetAudience(audience)
                        .build();
        GenericUrl genericUrl = new GenericUrl(serviceUrl);
        HttpCredentialsAdapter adapter = new HttpCredentialsAdapter(tokenCredential);
        HttpTransport transport = new NetHttpTransport();
        HttpRequest request = transport.createRequestFactory(adapter)
                .buildPostRequest(genericUrl, ByteArrayContent.fromString("application/json", jsonObj.toString()));
        request.execute();
    }

    /**
     * Method retrieves a list of prime numbers from the value two to the max specified in the parameter.
     *
     * @param max           The value to specify the max range
     * @return              The list of prime numbers
     */
    private static ArrayList<Integer> getPrimes(int max) {
        ArrayList<Integer> primes = new ArrayList<>();
        for (int i = 2; i <= max; i++) {                                        // Iterate through entire range
            boolean isPrime = true;
            for (int j = 2; j <= i/j; ++j) {                                    // Check if j is a factor of i
                if (i % j == 0) {                                               // If it is, do not add to list
                    isPrime = false;
                    break;
                }
            }
            if (isPrime)
                primes.add(i);
        }
        return primes;
    }
}
