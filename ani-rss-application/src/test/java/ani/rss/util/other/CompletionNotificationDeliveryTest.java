package ani.rss.util.other;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CompletionNotificationDeliveryTest {
    @TempDir Path directory;
    @Test void partialFailureRetriesOnlyUnsuccessfulChannelAndPersistsReceiptsAcrossRestart() {
        var file=directory.resolve("receipts.json").toFile();
        var clock=new AtomicLong(0);var first=new AtomicInteger();var second=new AtomicInteger();var ack=new AtomicInteger();
        var channels=List.of(new CompletionNotificationDelivery.Channel("mail",()->{first.incrementAndGet();return true;}),
                new CompletionNotificationDelivery.Channel("hook",()->second.incrementAndGet()>1));
        var delivery=new CompletionNotificationDelivery(file,Runnable::run,clock::get);
        delivery.submit("task",channels,()->{ack.incrementAndGet();return true;});
        assertEquals(0,ack.get());
        delivery.submit("task",channels,()->{ack.incrementAndGet();return true;});
        assertEquals(1,second.get());
        clock.set(300_001);
        delivery.submit("task",channels,()->{ack.incrementAndGet();return true;});
        assertEquals(1,first.get());assertEquals(2,second.get());assertEquals(1,ack.get());
        new CompletionNotificationDelivery(file,Runnable::run,clock::get).submit("task",channels,()->true);
        assertEquals(1,first.get());assertEquals(2,second.get());
    }
    @Test void concurrentPollingQueuesOneDeliveryAndNoAcknowledgementBeforeExecution() {
        var queue=new ArrayList<Runnable>();var calls=new AtomicInteger();var ack=new AtomicInteger();
        var delivery=new CompletionNotificationDelivery(directory.resolve("receipts.json").toFile(),queue::add,()->0);
        var channels=List.of(new CompletionNotificationDelivery.Channel("mail",()->{calls.incrementAndGet();return true;}));
        delivery.submit("task",channels,()->{ack.incrementAndGet();return true;});
        delivery.submit("task",channels,()->{ack.incrementAndGet();return true;});
        assertEquals(1,queue.size());assertEquals(0,ack.get());queue.getFirst().run();
        assertEquals(1,ack.get());assertEquals(1,calls.get());
    }
    @Test void failedTagWriteCanRetryWithoutResendingMail() {
        var clock=new AtomicLong(0);var sent=new AtomicInteger();
        var delivery=new CompletionNotificationDelivery(directory.resolve("receipts.json").toFile(),Runnable::run,clock::get);
        var channels=List.of(new CompletionNotificationDelivery.Channel("mail",()->{sent.incrementAndGet();return true;}));
        delivery.submit("task",channels,()->false);clock.set(300_001);
        delivery.submit("task",channels,()->true);assertEquals(1,sent.get());
    }
}
