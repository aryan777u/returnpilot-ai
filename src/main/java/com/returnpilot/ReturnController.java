package com.returnpilot;

import com.returnpilot.Models.Decision;
import com.returnpilot.Models.Order;
import com.returnpilot.Models.RefundRequest;
import com.returnpilot.Models.ReturnRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReturnController {

    private final OrderService orders;
    private final DecisionService decisions;
    private final PayPalService paypal;

    public ReturnController(OrderService orders, DecisionService decisions, PayPalService paypal) {
        this.orders = orders;
        this.decisions = decisions;
        this.paypal = paypal;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paypal", paypal.isConfigured());
        m.put("gemini", decisions.isConfigured());
        return m;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Order o : orders.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id());
            m.put("customer", o.customer());
            m.put("item", o.item());
            m.put("price", o.price());
            m.put("deliveredDaysAgo", o.deliveredDaysAgo());
            m.put("finalSale", o.finalSale());
            m.put("paid", orders.captureFor(o) != null);
            out.add(m);
        }
        return out;
    }

    @PostMapping("/pay/{orderId}")
    public ResponseEntity<?> pay(@PathVariable String orderId) {
        Order order = orders.find(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown order"));
        }
        if (!paypal.isConfigured()) {
            return ResponseEntity.badRequest().body(Map.of("error", "PayPal sandbox credentials are not set"));
        }
        try {
            String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            String[] r = paypal.createOrder(order.id(), order.item(), order.price(),
                    base + "/paypal/return", base + "/paypal/cancel");
            return ResponseEntity.ok(Map.of("approveUrl", r[1]));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/returns/analyze")
    public ResponseEntity<?> analyze(@RequestBody ReturnRequest req) {
        Order order = orders.find(req.orderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown order"));
        }
        Decision d = decisions.decide(order, req.reason() == null ? "" : req.reason());
        return ResponseEntity.ok(d);
    }

    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody RefundRequest req) {
        Order order = orders.find(req.orderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown order"));
        }
        if (req.amount() <= 0 || req.amount() > order.price()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid refund amount"));
        }

        if (!paypal.isConfigured()) {
            Map<String, Object> sim = new LinkedHashMap<>();
            sim.put("mode", "SIMULATED");
            sim.put("status", "COMPLETED");
            sim.put("refundId", "SIM-" + System.currentTimeMillis());
            sim.put("amount", req.amount());
            return ResponseEntity.ok(sim);
        }

        String captureId = orders.captureFor(order);
        if (captureId == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "This order has no PayPal payment yet. Click \"Pay with PayPal (sandbox)\" first."));
        }
        try {
            return ResponseEntity.ok(paypal.refund(captureId, req.amount()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
