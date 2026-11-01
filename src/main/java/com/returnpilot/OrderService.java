package com.returnpilot;

import com.returnpilot.Models.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {

    public static final String POLICY = """
            - Returns accepted within 30 days of delivery for a full refund.
            - Defective or damaged items are accepted within 90 days of delivery for a full refund.
            - Items marked final sale cannot be returned.
            - Items returned after 30 days (not defective) may receive at most a 50% store-discretion refund.
            """;

    private final List<Order> orders;
    /** orderId -> PayPal capture id, filled in after a sandbox payment is captured. */
    private final Map<String, String> captures = new ConcurrentHashMap<>();

    public OrderService(@Value("${paypal.demo-capture-id:}") String demoCaptureId) {
        this.orders = List.of(
                new Order("ORD-1001", "Maya Patel", "Wireless Headphones", 79.99, 5, false, demoCaptureId),
                new Order("ORD-1002", "Liam Chen", "Running Shoes", 120.00, 45, false, null),
                new Order("ORD-1003", "Sofia Garcia", "Clearance Desk Lamp", 39.50, 10, true, null),
                new Order("ORD-1004", "Noah Williams", "Smart Watch", 199.00, 3, false, null)
        );
    }

    public List<Order> all() {
        return orders;
    }

    public Optional<Order> find(String id) {
        return orders.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public void saveCapture(String orderId, String captureId) {
        captures.put(orderId, captureId);
    }

    /** The PayPal capture id for this order, or null if it has not been paid yet. */
    public String captureFor(Order order) {
        String c = captures.get(order.id());
        if (c != null) return c;
        return (order.captureId() == null || order.captureId().isBlank()) ? null : order.captureId();
    }
}
