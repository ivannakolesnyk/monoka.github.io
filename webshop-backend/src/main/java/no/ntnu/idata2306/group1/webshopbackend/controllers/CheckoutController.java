package no.ntnu.idata2306.group1.webshopbackend.controllers;

import com.google.gson.JsonSyntaxException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.servlet.http.HttpServletRequest;
import no.ntnu.idata2306.group1.webshopbackend.dto.CartRequest;
import no.ntnu.idata2306.group1.webshopbackend.models.*;
import no.ntnu.idata2306.group1.webshopbackend.repositories.OrderLineRepository;
import no.ntnu.idata2306.group1.webshopbackend.repositories.ProductRepository;
import no.ntnu.idata2306.group1.webshopbackend.repositories.ShopOrderRepository;
import no.ntnu.idata2306.group1.webshopbackend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CheckoutController is a REST controller that handles requests to create
 * checkout sessions and manage Stripe webhooks.
 *
 * @author Mark
 */
@RestController
public class CheckoutController {

    private static final String STRIPE_WEBHOOK_SECRET = "whsec_ae49e0485ed5e7850ef2111680449350829e29c4eb5ba80fb8a373a9a8b270f8";
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShopOrderRepository shopOrderRepository;
    @Autowired
    private OrderLineRepository orderLineRepository;
    private String sessionId;

    /**
     * Creates a checkout session based on the given cart items and user ID.
     *
     * @param cartRequest the request object containing cart items and user ID
     * @return the checkout session URL
     */
    @PostMapping("/api/create-checkout-session")
    public ResponseEntity<Map<String, String>> createCheckoutSession(@RequestBody CartRequest cartRequest) {
        List<CartItem> cart = cartRequest.getCart();
        String userId = cartRequest.getUserId();
        List<SessionCreateParams.LineItem> lineItems = cart.stream().map(item -> {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) {
                return null;
            }

            return SessionCreateParams.LineItem.builder().setQuantity(item.getQuantity().longValue()).setPriceData(SessionCreateParams.LineItem.PriceData.builder().setCurrency("nok").setUnitAmount((long) (product.getPrice() * 100)).setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder().setName(product.getName()).build()).build()).build();
        }).filter(item -> item != null).collect(Collectors.toList());

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder().addAllLineItem(lineItems).addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD).setMode(SessionCreateParams.Mode.PAYMENT).setSuccessUrl("http://localhost:3000/profile/vieworders").setCancelUrl("http://localhost:3000/shoppingcart").putMetadata("user_id", String.valueOf(userId));

        // Add the product IDs and quantities to the metadata
        for (int i = 0; i < cart.size(); i++) {
            CartItem item = cart.get(i);
            paramsBuilder.putMetadata("product_" + (i + 1), item.getProductId() + "," + item.getQuantity());
        }

        SessionCreateParams params = paramsBuilder.build();


        try {
            Session session = Session.create(params);
            Map<String, String> responseData = new HashMap<>();
            responseData.put("url", session.getUrl());
            // Save the session id in order to retrieve on session completion
            this.sessionId = session.getId();
            return ResponseEntity.ok(responseData);
        } catch (StripeException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }


    /**
     * Handles Stripe webhook events, specifically "checkout.session.completed".
     *
     * @param payload the webhook payload
     * @param request the HTTP request object
     * @return an HTTP response indicating the result of handling the webhook
     */
    @PostMapping("/api/webhook")
    public ResponseEntity<String> handleStripeWebhook(@RequestBody String payload, HttpServletRequest request) {
        String sigHeader = request.getHeader("Stripe-Signature");
        Event event = null;

        try {
            event = Webhook.constructEvent(payload, sigHeader, STRIPE_WEBHOOK_SECRET);
        } catch (JsonSyntaxException e) {
            // Invalid payload
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("");
        } catch (SignatureVerificationException e) {
            // Invalid signature
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("");
        }

        // Handle the event
        if (event.getType().equals("checkout.session.completed")) {
            Session session = null;
            try {
                session = Session.retrieve(this.sessionId);
            } catch (StripeException e) {
                e.printStackTrace();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("");
            }

            if (session == null) {
                System.out.println("Session data not available");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("");
            }

            // Retrieve userId and product information from metadata
            String userId = session.getMetadata().get("user_id");

            // Use the metadata to create a list of OrderLine objects
            List<OrderLine> orderLines = new ArrayList<>();

            session.getMetadata().forEach((key, value) -> {
                if (key.startsWith("product_")) {
                    String[] productIdAndQuantity = value.split(",");
                    int productId = Integer.parseInt(productIdAndQuantity[0]);
                    int quantity = Integer.parseInt(productIdAndQuantity[1]);

                    Product product = productRepository.findById(productId).orElse(null);

                    if (product != null) {
                        OrderLine orderLine = new OrderLine(null, product, quantity, product.getPrice());
                        orderLines.add(orderLine);
                    }
                }
            });

            User user = userRepository.findByEmail(userId).orElse(null);
            if (user != null) {
                // Create and save the ShopOrder
                ShopOrder shopOrder = new ShopOrder(new Date(), user, "Paid");
                shopOrderRepository.save(shopOrder);

                // Update the OrderLines with the saved ShopOrder
                orderLines.forEach(orderLine -> orderLine.setOrder(shopOrder));

                // Save the OrderLines
                orderLineRepository.saveAll(orderLines);
            }
        }

        return ResponseEntity.ok("");
    }
}
