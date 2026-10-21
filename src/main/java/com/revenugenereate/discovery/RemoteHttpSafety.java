package com.revenugenereate.discovery;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Validates remote destinations and bounded HTTP response bodies used by discovery adapters. */
public final class RemoteHttpSafety {
    private RemoteHttpSafety() {}

    static URI validate(URI uri, boolean allowPrivate) throws Exception {
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Provider URL must be an absolute HTTP(S) URL without credentials");
        }
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (!allowPrivate && List.of(addresses).stream().anyMatch(RemoteHttpSafety::isUnsafeAddress)) {
            throw new IllegalArgumentException("Provider URL resolves to a restricted network address");
        }
        return uri;
    }

    public static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(int maxBytes) {
        return responseInfo -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            private int total;

            @Override public CompletionStage<byte[]> getBody() { return body; }

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                subscription.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                try {
                    for (ByteBuffer buffer : buffers) {
                        int count = buffer.remaining();
                        total += count;
                        if (total > maxBytes) {
                            subscription.cancel();
                            body.completeExceptionally(
                                    new IOException("Provider response exceeds configured size limit"));
                            return;
                        }
                        byte[] bytes = new byte[count];
                        buffer.get(bytes);
                        output.write(bytes);
                    }
                    subscription.request(1);
                } catch (IOException exception) {
                    subscription.cancel();
                    body.completeExceptionally(exception);
                }
            }

            @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
            @Override public void onComplete() { body.complete(output.toByteArray()); }
        };
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
