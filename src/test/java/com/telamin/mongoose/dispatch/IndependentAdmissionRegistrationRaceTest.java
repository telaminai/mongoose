package com.telamin.mongoose.dispatch;
import com.telamin.mongoose.exception.PublishRefusedException;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class IndependentAdmissionRegistrationRaceTest {
    @Test void deadTargetJoiningBeforeWaiterRegistrationCannotLoseItsWakeup() throws Exception {
        var publisher=new EventToQueuePublisher<Object>("feed");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(20))));
        var first=new OneToOneConcurrentArrayQueue<Object>(2);first.offer(1);first.offer(2);
        publisher.addTargetQueue(first,"original-full");
        var reachedRegistration=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        var delegate=ConcurrentHashMap.<Thread>newKeySet();
        // Scheduling hook only: pause at the real gap between admission snapshot and waiter registration.
        var waiters=new AbstractSet<Thread>() {
            @Override public Iterator<Thread> iterator(){return delegate.iterator();}
            @Override public int size(){return delegate.size();}
            @Override public boolean remove(Object o){return delegate.remove(o);}
            @Override public boolean add(Thread t){
                reachedRegistration.countDown();
                try {assertTrue(proceed.await(3,TimeUnit.SECONDS));}
                catch(InterruptedException e){throw new AssertionError(e);}
                return delegate.add(t);
            }
        };
        var field=EventToQueuePublisher.class.getDeclaredField("waiters");field.setAccessible(true);field.set(publisher,waiters);
        var outcome=new AtomicReference<Throwable>();
        Thread sender=new Thread(()->{try{publisher.publish("input");}catch(Throwable t){outcome.set(t);}});
        sender.start();
        try {
            assertTrue(reachedRegistration.await(3,TimeUnit.SECONDS));
            publisher.addTargetQueue(new OneToOneConcurrentArrayQueue<Object>(2),"joined-dead");
            publisher.bindContainment("joined-dead",()->true,()->0L);
            proceed.countDown();
            sender.join(1000);
            assertFalse(sender.isAlive(),"terminal target cancellation must be observed before the original 20 second capacity deadline");
            var refused=assertInstanceOf(PublishRefusedException.class,outcome.get());
            assertEquals(PublishRefusedException.Reason.TARGET_UNAVAILABLE,refused.reason());
            assertEquals(0,publisher.getSequenceNumber());
        } finally {proceed.countDown();sender.interrupt();sender.join(2000);publisher.cancelBlockedPublishes();}
    }
}
