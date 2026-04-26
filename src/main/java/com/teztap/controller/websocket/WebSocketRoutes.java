package com.teztap.controller.websocket;

public final class WebSocketRoutes {

    // Prevent instantiation
    private WebSocketRoutes() {}

    // --- User Specific Queues (P2P) ---
    public static final String COURIER_DELIVERY_OFFER = "/queue/delivery";
    public static final String CUSTOMER_DELIVERY_STATUS = "/queue/delivery/status";

    // --- Global Topics (Broadcast) ---
    public static final String TOPIC_MARKET_UPDATES = "/topic/market";
    public static final String TOPIC_SYSTEM_ALERTS = "/topic/alerts";

    // --- Incoming Message Mappings (From Client to Server) ---
    public static final String INBOUND_DELIVERY_OFFER_RESPONSE = "/accept-delivery";
    public static final String INBOUND_DELIVERY_FINISHED = "/delivery-finished";
}
