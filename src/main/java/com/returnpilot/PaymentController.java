package com.returnpilot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Handles the browser coming back from PayPal after the buyer approves or cancels. */
@Controller
public class PaymentController {

    private final PayPalService paypal;
    private final OrderService orders;

    public PaymentController(PayPalService paypal, OrderService orders) {
        this.paypal = paypal;
        this.orders = orders;
    }

    @GetMapping("/paypal/return")
    public String onReturn(@RequestParam("token") String token) {
        try {
            String[] r = paypal.captureOrder(token);
            orders.saveCapture(r[0], r[1]);
            return "redirect:/?paid=" + r[0];
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.length() > 160) msg = msg.substring(0, 160);
            return "redirect:/?error=" + URLEncoder.encode(msg, StandardCharsets.UTF_8);
        }
    }

    @GetMapping("/paypal/cancel")
    public String onCancel() {
        return "redirect:/?cancelled=1";
    }
}
