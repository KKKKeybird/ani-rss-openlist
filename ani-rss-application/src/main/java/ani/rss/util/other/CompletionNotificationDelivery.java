package ani.rss.util.other;

import ani.rss.handle.JsonFile;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Per-channel acknowledgements survive polling and restarts; no secrets are persisted. */
@Slf4j
final class CompletionNotificationDelivery {
    record Channel(String key, BooleanSupplier send) {}
    private final JsonFile file;
    private final Set<String> receipts;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Executor executor;
    private final LongSupplier clock;

    CompletionNotificationDelivery(File path, Executor executor, LongSupplier clock) {
        if (!path.getParentFile().isDirectory() && !path.getParentFile().mkdirs()) {
            throw new IllegalStateException("Cannot create notification receipt directory");
        }
        file = JsonFile.getInstance(path);
        receipts = new HashSet<>(file.toList(String.class, new ArrayList<>()));
        this.executor = executor;
        this.clock = clock;
    }

    synchronized void submit(String task, List<Channel> channels, BooleanSupplier acknowledge) {
        if (pending.contains(task) || clock.getAsLong() < retryAt.getOrDefault(task, 0L)) return;
        pending.add(task);
        try {
            executor.execute(() -> {
                boolean success = false;
                try {
                    boolean all = true;
                    for (var channel : channels) {
                        String key = task + ":" + channel.key();
                        synchronized (this) { if (receipts.contains(key)) continue; }
                        try {
                            if (!channel.send().getAsBoolean()) { all = false; continue; }
                            synchronized (this) {
                                receipts.add(key);
                                try { file.writer(new ArrayList<>(receipts)); }
                                catch (Exception e) { receipts.remove(key); throw e; }
                            }
                        } catch (Exception e) {
                            all = false;
                            log.warn("完成通知渠道待重试 task={} channel={}: {}", task, channel.key(), e.getClass().getSimpleName());
                        }
                    }
                    if (all) success = acknowledge.getAsBoolean();
                } catch (Exception e) {
                    log.warn("完成通知确认待重试 task={}: {}", task, e.getClass().getSimpleName());
                } finally {
                    synchronized (this) {
                        pending.remove(task);
                        if (success) retryAt.remove(task);
                        else retryAt.put(task, clock.getAsLong() + 300_000L);
                    }
                }
            });
        } catch (RuntimeException e) {
            pending.remove(task);
            throw e;
        }
    }
}
