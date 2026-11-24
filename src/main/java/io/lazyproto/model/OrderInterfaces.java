package io.lazyproto.model;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import java.util.List;

/**
 * Shared Protobuf OrBuilder interfaces (matching official protoc generated interfaces).
 * Both StandardOrderProto (Eager) and LazyOrderProto (Lazy + Input-Reusing) implement these interfaces.
 */
public final class OrderInterfaces {
    private OrderInterfaces() {}

    public interface UserProfileOrBuilder extends MessageOrBuilder {
        long getUserId();
        String getCity();
        ByteString getCityBytes();
        int getVipLevel();
        double getScore();
        String getEmail();
        ByteString getEmailBytes();
    }

    public interface LineItemOrBuilder extends MessageOrBuilder {
        long getItemId();
        String getSku();
        ByteString getSkuBytes();
        int getQuantity();
        long getPriceCents();
        String getDescription();
        ByteString getDescriptionBytes();
    }

    public interface OrderEnvelopeOrBuilder extends MessageOrBuilder {
        long getOrderId();
        String getBuyerName();
        ByteString getBuyerNameBytes();
        int getStatus();
        long getUpdatedAtFixed64();
        boolean hasUser();
        UserProfileOrBuilder getUserOrBuilder();
        List<? extends LineItemOrBuilder> getItemsOrBuilderList();
        int getItemsCount();
        LineItemOrBuilder getItemsOrBuilder(int index);
        ByteString getPayload();
        String getAuthToken();
        ByteString getAuthTokenBytes();
        String getTraceId();
        ByteString getTraceIdBytes();
        long getTotalCents();
    }
}
