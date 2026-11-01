package com.returnpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.returnpilot.Models.Decision;
import com.returnpilot.Models.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Service
public class DecisionService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String model;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Decision decide(Order order, String reason) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return askGemini(order, reason);
            } catch (Exception e) {
                Decision fallback = rules(order, reason);
                return new Decision(fallback.action(), fallback.refundAmount(), fallback.explanation(),
                        "Gemini call failed (" + e.getMessage() + "); used the rules engine instead.",
                        "rules");
            }
        }
        return rules(order, reason);
    }

    private Decision askGemini(Order order, String reason) throws Exception {
        String prompt = """
                You are a returns specialist for an online store. Decide on this return request
                strictly using the store policy. Be fair to the customer and protect the merchant.

                STORE POLICY:
                %s

                ORDER:
                - Order ID: %s
                - Item: %s
                - Price paid (USD): %.2f
                - Delivered %d days ago
                - Final sale item: %s

                CUSTOMER'S REASON:
                "%s"

                Respond with JSON only, using exactly these fields:
                {"action": "APPROVE_FULL" | "APPROVE_PARTIAL" | "DENY" | "NEEDS_REVIEW",
                 "refundAmount": number in USD (0 if denied),
                 "explanation": "2-3 sentences for the merchant citing the policy",
                 "riskNote": "one sentence on any fraud or abuse signals, or 'No concerns.'"}
                """.formatted(OrderService.POLICY, order.id(), order.item(), order.price(),
                order.deliveredDaysAgo(), order.finalSale(), reason.replace("\"", "'"));

        String body = mapper.writeValueAsString(Map.of(
                "contents", new Object[]{Map.of("parts", new Object[]{Map.of("text", prompt)})},
                "generationConfig", Map.of("responseMimeType", "application/json")));

        HttpResponse<String> response = sendWithRetry(body);

        String text = mapper.readTree(response.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
        JsonNode json = mapper.readTree(text.replaceAll("```json|```", "").trim());

        double amount = Math.max(0, Math.min(order.price(), json.path("refundAmount").asDouble(0)));
        return new Decision(
                json.path("action").asText("NEEDS_REVIEW"),
                amount,
                json.path("explanation").asText(""),
                json.path("riskNote").asText(""),
                "gemini");
    }

    /** Calls Gemini, retrying when the service is busy and falling back to a lighter model. */
    private HttpResponse<String> sendWithRetry(String body) throws Exception {
        String[] models = {model, "gemini-flash-lite-latest"};
        IllegalStateException last = new IllegalStateException("no attempt made");
        for (String m : models) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent"))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return response;
                }
                last = new IllegalStateException("HTTP " + status + " from " + m);
                if (status == 503 || status == 429 || status == 500) {
                    Thread.sleep(1500L * attempt);
                    continue;
                }
                break;
            }
        }
        throw last;
    }

    Decision rules(Order order, String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        boolean defective = r.contains("defect") || r.contains("broken") || r.contains("damag")
                || r.contains("not working") || r.contains("stopped working") || r.contains("faulty");
        int days = order.deliveredDaysAgo();

        if (order.finalSale()) {
            return new Decision("DENY", 0,
                    "This item was sold as final sale, which the policy does not allow to be returned.",
                    "No concerns.", "rules");
        }
        if (defective && days <= 90) {
            return new Decision("APPROVE_FULL", order.price(),
                    "The customer reports a defect and the order is within the 90-day defective-item window.",
                    "Consider requesting a photo of the defect.", "rules");
        }
        if (days <= 30) {
            return new Decision("APPROVE_FULL", order.price(),
                    "The return is within the 30-day return window, so a full refund applies.",
                    "No concerns.", "rules");
        }
        double half = Math.round(order.price() * 50) / 100.0;
        return new Decision("APPROVE_PARTIAL", half,
                "The return is outside the 30-day window and no defect was reported, so at most a 50% refund applies.",
                "Merchant discretion recommended.", "rules");
    }
}
