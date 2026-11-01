package com.returnpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PayPalService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** PayPal order id -> our order id, for matching the buyer's return to the right order. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();

    @Value("${paypal.base-url}")
    private String baseUrl;

    @Value("${paypal.client-id:}")
    private String clientId;

    @Value("${paypal.client-secret:}")
    private String clientSecret;

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String money(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    /** Creates a PayPal checkout order. Returns {paypalOrderId, approvalUrl}. */
    public String[] createOrder(String ourOrderId, String description, double amount,
                                String returnUrl, String cancelUrl) throws Exception {
        String token = accessToken();
        Map<String, Object> body = m(
                "intent", "CAPTURE",
                "purchase_units", List.of(m(
                        "reference_id", ourOrderId,
                        "description", description,
                        "amount", m("currency_code", "USD", "value", money(amount)))),
                "payment_source", m("paypal", m("experience_context", m(
                        "brand_name", "ReturnPilot Demo Store",
                        "user_action", "PAY_NOW",
                        "return_url", returnUrl,
                        "cancel_url", cancelUrl))));

        JsonNode json = post(token, "/v2/checkout/orders", mapper.writeValueAsString(body));
        String paypalOrderId = json.path("id").asText();
        String approveUrl = null;
        for (JsonNode link : json.path("links")) {
            String rel = link.path("rel").asText();
            if (rel.equals("payer-action") || rel.equals("approve")) {
                approveUrl = link.path("href").asText();
                break;
            }
        }
        if (paypalOrderId.isBlank() || approveUrl == null) {
            throw new IllegalStateException("PayPal did not return an approval link");
        }
        pending.put(paypalOrderId, ourOrderId);
        return new String[]{paypalOrderId, approveUrl};
    }

    /** Captures an approved PayPal order. Returns {ourOrderId, captureId}. */
    public String[] captureOrder(String paypalOrderId) throws Exception {
        String ourOrderId = pending.get(paypalOrderId);
        if (ourOrderId == null) {
            throw new IllegalStateException("Unknown PayPal order (the app may have restarted)");
        }
        String token = accessToken();
        JsonNode json = post(token, "/v2/checkout/orders/" + paypalOrderId + "/capture", "{}");
        String captureId = json.path("purchase_units").path(0).path("payments")
                .path("captures").path(0).path("id").asText();
        if (captureId.isBlank()) {
            throw new IllegalStateException("Payment was not captured (status: " + json.path("status").asText() + ")");
        }
        pending.remove(paypalOrderId);
        return new String[]{ourOrderId, captureId};
    }

    /** Refunds a captured PayPal payment. */
    public Map<String, Object> refund(String captureId, double amount) throws Exception {
        String token = accessToken();
        String body = mapper.writeValueAsString(m("amount", m("value", money(amount), "currency_code", "USD")));
        JsonNode json = post(token, "/v2/payments/captures/" + captureId + "/refund", body);
        return m("mode", "PAYPAL_SANDBOX",
                "status", json.path("status").asText(),
                "refundId", json.path("id").asText(),
                "amount", amount);
    }

    private JsonNode post(String token, String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("PayPal-Request-Id", UUID.randomUUID().toString())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("PayPal returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private String accessToken() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/oauth2/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("PayPal login failed (HTTP " + response.statusCode()
                    + "). Check your Client ID and Secret are Sandbox credentials.");
        }
        return mapper.readTree(response.body()).path("access_token").asText();
    }
}
