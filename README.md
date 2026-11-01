# ReturnPilot AI

An AI copilot for online sellers that reviews customer return requests against the store's
policy, recommends approve / partial / deny with a plain-language explanation, and issues
the refund through the PayPal sandbox after the merchant approves.

## Run

Requires Java 17 or newer. No separate Maven install needed.

Windows:

    .\mvnw.cmd spring-boot:run

Mac/Linux:

    ./mvnw spring-boot:run

Open http://localhost:8080

## Optional configuration (environment variables)

| Variable | Purpose |
|---|---|
| GEMINI_API_KEY | Enables Gemini decisions. Without it, a built-in rules engine is used. |
| PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET | PayPal sandbox app credentials. |
| PAYPAL_DEMO_CAPTURE_ID | A sandbox capture ID; refunds for order ORD-1001 will hit the real sandbox API. |

Without PayPal credentials, refunds are simulated so the demo flow still works.

## License

MIT
