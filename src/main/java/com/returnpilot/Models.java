package com.returnpilot;

public final class Models {
    private Models() {}

    public record Order(String id, String customer, String item, double price,
                        int deliveredDaysAgo, boolean finalSale, String captureId) {}

    public record ReturnRequest(String orderId, String reason) {}

    public record RefundRequest(String orderId, double amount) {}

    public record Decision(String action, double refundAmount, String explanation,
                           String riskNote, String source) {}
}
