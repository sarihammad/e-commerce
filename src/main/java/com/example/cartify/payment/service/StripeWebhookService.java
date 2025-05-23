package com.example.cartify.payment.service;

import com.example.cartify.auth.repository.UserRepository;
import com.example.cartify.common.service.EmailService;
import com.example.cartify.order.model.Order;
import com.example.cartify.order.service.OrderService;
import com.example.cartify.payment.model.Payment;
import com.example.cartify.payment.repository.PaymentRepository;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final OrderService orderService;    

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;

    public void processWebhook(String payload, String sigHeader) throws StripeException {
        Event event = Webhook.constructEvent(payload, sigHeader, endpointSecret);

        if ("checkout.session.completed".equals(event.getType())) {
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            Session session = (Session) dataObjectDeserializer.getObject().orElseThrow();
            Order order = orderService.findLatestOrderByUserEmail(session.getCustomerEmail());

            String email = session.getCustomerEmail();
            String stripeCustomerId = session.getCustomer();
        
            userRepository.findByEmail(email).ifPresent(user -> {
                if (user.getStripeCustomerId() == null) {
                    user.setStripeCustomerId(stripeCustomerId);
                    userRepository.save(user);
                    log.info("Updated user {} with Stripe customer ID {}", email, stripeCustomerId);
                }
            });


            Payment payment = Payment.builder()
                    .stripeSessionId(session.getId())
                    .userEmail(session.getCustomerEmail())
                    .amount(session.getAmountTotal() / 100.0) // Convert cents to dollars
                    .currency(session.getCurrency())
                    .status("COMPLETED")
                    .createdAt(Instant.now())
                    .order(order)
                    .build();

            paymentRepository.save(payment);
            log.info("Payment persisted for session {}", session.getId());

            // Send email notification
            try {
                double amount = session.getAmountTotal() / 100.0;
                String currency = session.getCurrency();

                String body = """
                    <h2>Payment Confirmation</h2>
                    <p>Thank you for your payment of <strong>%.2f %s</strong>.</p>
                    <p>Your payment was successful. Order will be processed shortly.</p>
                """.formatted(amount, currency.toUpperCase());

                emailService.sendEmail(
                        session.getCustomerEmail(),
                        "Payment Confirmation",
                        body
                );
                log.info("Payment confirmation email sent to {}", session.getCustomerEmail());
            } catch (Exception e) {
                log.error("Failed to send payment confirmation email to {}", session.getCustomerEmail(), e);
            }
        }
    }
}