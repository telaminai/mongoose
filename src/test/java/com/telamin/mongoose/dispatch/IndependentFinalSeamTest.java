package com.telamin.mongoose.dispatch;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.service.*;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class IndependentFinalSeamTest {
    static void awaitPark(Thread thread) throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(thread.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<end)Thread.sleep(1);
        assertEquals(Thread.State.TIMED_WAITING,thread.getState());
    }
    @Test void newlyAddedDeadTargetMustBeCheckedBeforeWaitingPublishCommits() throws Exception {
        var publisher=new EventToQueuePublisher<Object>("feed");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(10))));
        var first=new OneToOneConcurrentArrayQueue<Object>(2);first.offer(1);first.offer(2);
        publisher.addTargetQueue(first,"first");
        var outcome=new AtomicReference<Throwable>();
        Thread sender=new Thread(()->{try{publisher.publish("input");}catch(Throwable t){outcome.set(t);}});
        sender.start();
        try {
            awaitPark(sender);
            var added=new OneToOneConcurrentArrayQueue<Object>(2);
            publisher.addTargetQueue(added,"newly-dead");
            publisher.bindContainment("newly-dead",()->true,()->0L);
            first.poll();
            sender.join(2000);
            assertFalse(sender.isAlive());
            var failure=assertInstanceOf(PublishRefusedException.class,outcome.get(),"newly joined dead subscriber must cancel admission");
            assertEquals(PublishRefusedException.Reason.TARGET_UNAVAILABLE,failure.reason());
            assertEquals(0,publisher.getSequenceNumber());
            assertTrue(added.isEmpty());
        } finally {publisher.cancelBlockedPublishes();sender.interrupt();sender.join(2000);}
    }
    @Test void newlyJoinedBlockerMustRemainInCancellationEvidenceAfterRemoval() throws Exception {
        var publisher=new EventToQueuePublisher<Object>("feed");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(10))));
        var first=new OneToOneConcurrentArrayQueue<Object>(2);first.offer(1);first.offer(2);
        publisher.addTargetQueue(first,"first");
        var outcome=new AtomicReference<Throwable>();
        Thread sender=new Thread(()->{try{publisher.publish("input");}catch(Throwable t){outcome.set(t);}},"new-blocker-sender");
        sender.start();
        try {
            awaitPark(sender);
            var checkedNewTarget=new CountDownLatch(1);
            var added=new OneToOneConcurrentArrayQueue<Object>(2) {
                @Override public int remainingCapacity() {
                    if(Thread.currentThread()==sender) checkedNewTarget.countDown();
                    return super.remainingCapacity();
                }
            };
            added.offer(3);added.offer(4);
            var dead=new java.util.concurrent.atomic.AtomicBoolean();
            publisher.addTargetQueue(added,"joined-blocker");
            publisher.bindContainment("joined-blocker",dead::get,()->0L);
            first.poll();
            assertTrue(checkedNewTarget.await(2,TimeUnit.SECONDS),"waiting admission now evaluates the newly joined blocker");
            dead.set(true);
            publisher.removeTargetQueueByName("joined-blocker");
            sender.join(2000);
            assertFalse(sender.isAlive());
            var failure=assertInstanceOf(PublishRefusedException.class,outcome.get(),"fenced joined blocker must not disappear from cancellation evidence");
            assertEquals(PublishRefusedException.Reason.TARGET_UNAVAILABLE,failure.reason());
            assertEquals(0,publisher.getSequenceNumber());
        } finally {publisher.cancelBlockedPublishes();sender.interrupt();sender.join(2000);}
    }
    static class Feed implements EventFlowService<Object> {
        volatile EventToQueuePublisher<Object> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<Object> p){publisher=p;}
        public void subscribe(EventSubscriptionKey<Object> key){}
        public void unSubscribe(EventSubscriptionKey<Object> key){}
    }
    static class Processor implements DataFlow {public void init(){} public void onEvent(Object e){} public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed feed){} public void start(){} public void stop(){} public void tearDown(){} }
    @Test void groupStatusMustNotWaitForAnUnrelatedPublishMapper() throws Exception {
        var server=new MongooseServer(new MongooseServerConfig());
        var unrelated=new Feed();server.registerEventSource("unrelated",unrelated);
        server.addEventProcessor("p","healthy",null,Processor::new);server.init();server.start();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            unrelated.publisher.setDataMapper(value->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return value;});
            Future<?> send=pool.submit(()->unrelated.publisher.publish("held"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> status=pool.submit(()->server.controlGroupStatus("healthy"));
            assertDoesNotThrow(()->status.get(250,TimeUnit.MILLISECONDS),"nonblocking group diagnostics must not wait for an unrelated feed's user mapper");
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));server.shutdown();}
    }
    @Test void quietRecoveryDiagnosticsDoNotWaitForTheirOwnMapper() throws Exception {
        var publisher=new EventToQueuePublisher<Object>("feed");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO))
                .withQuietPeriod(Duration.ofSeconds(1)));
        var queue=new OneToOneConcurrentArrayQueue<Object>(2);queue.offer(1);queue.offer(2);
        publisher.addTargetQueue(queue,"q",com.telamin.mongoose.internal.NoOpCountersService.INSTANCE.counter("n"),
                null,"g","g/feed");
        publisher.publish("lost");
        assertEquals("DEGRADED",publisher.groupOverloadStates("g").get("q"));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            publisher.setDataMapper(value->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return value;});
            var send=pool.submit(()->publisher.publish("held"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Thread.sleep(1100);
            var view=pool.submit(()->publisher.groupOverloadStates("g"));
            assertEquals("UP",view.get(250,TimeUnit.MILLISECONDS).get("q"),"quiet-period recovery remains visible without the publisher monitor");
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }

}
