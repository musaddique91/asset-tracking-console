package com.zonewatch.stream;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live clients connected to this instance. Each client has a bounded queue drained by its own
 * virtual thread, so one slow browser never delays the others or the poller. A client whose queue
 * overflows is disconnected; the console reconnects and re-synchronises.
 */
@Component
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);
    private static final int QUEUE = 256;

    record Message(String event, Object data) {
    }

    final class Client {
        final SseEmitter emitter;
        final String user;
        final BlockingQueue<Message> queue = new ArrayBlockingQueue<>(QUEUE);
        volatile boolean closed;

        Client(SseEmitter emitter, String user) {
            this.emitter = emitter;
            this.user = user;
        }

        boolean offer(Message m) {
            if (closed) {
                return false;
            }
            if (!queue.offer(m)) {
                log.warn("live client of {} too slow, disconnecting", user);
                close();
                return false;
            }
            return true;
        }

        void close() {
            if (!closed) {
                closed = true;
                clients.remove(this);
                emitter.complete();
            }
        }

        void drain() {
            try {
                while (!closed) {
                    Message m = queue.poll(5, TimeUnit.SECONDS);
                    if (m == null) {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } else {
                        emitter.send(SseEmitter.event().name(m.event()).data(m.data()));
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // client went away
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();

    Client register(SseEmitter emitter, String user) {
        Client c = new Client(emitter, user);
        clients.add(c);
        emitter.onCompletion(c::close);
        emitter.onTimeout(c::close);
        emitter.onError(e -> c.close());
        Thread.ofVirtual().name("sse-" + user).start(c::drain);
        return c;
    }

    void broadcast(String event, Object data) {
        Message m = new Message(event, data);
        for (Client c : clients) {
            c.offer(m);
        }
    }

    /**
     * On shutdown, end live streams at once instead of letting graceful shutdown wait for them:
     * the browsers reconnect through the load balancer to the other instance.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        for (Client c : clients) {
            c.close();
        }
    }

    public int clientCount() {
        return clients.size();
    }

    boolean hasClients() {
        return !clients.isEmpty();
    }
}
