package com.plantit.queue.scaler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP wrapper for the Pterodactyl Client API.
 * Uses only the Java 21 built-in HTTP client — no extra dependencies.
 */
public class PterodactylClient {

    private final String panelUrl;
    private final String apiKey;
    private final HttpClient http;

    public PterodactylClient(String panelUrl, String apiKey) {
        this.panelUrl = panelUrl.stripTrailing().replaceAll("/$", "");
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Sends a power signal to a server. Signal: "start", "stop", "restart", "kill". */
    public void sendPowerSignal(String serverIdentifier, String signal) throws IOException, InterruptedException {
        String body = "{\"signal\":\"" + signal + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(panelUrl + "/api/client/servers/" + serverIdentifier + "/power"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Pterodactyl API error " + response.statusCode() + ": " + response.body());
        }
    }

    /** Returns the current power state: "running", "starting", "stopping", "offline". */
    public String getPowerState(String serverIdentifier) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(panelUrl + "/api/client/servers/" + serverIdentifier + "/resources"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Pterodactyl API error " + response.statusCode() + ": " + response.body());
        }

        // Parse "current_state" from the JSON without pulling in a JSON library
        String body = response.body();
        int idx = body.indexOf("\"current_state\"");
        if (idx == -1) return "unknown";
        int start = body.indexOf('"', idx + 16) + 1;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
