package io.meetscribe.app.desktop;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class DesktopIntegrationTest {
    static class Session implements DesktopIntegration.HotkeySession {
        CountDownLatch queue=new CountDownLatch(1), allowQueue=new CountDownLatch(1), registered=new CountDownLatch(1), unregistered=new CountDownLatch(1);
        BlockingQueue<Integer> messages=new LinkedBlockingQueue<>(); AtomicInteger registrations=new AtomicInteger(),quits=new AtomicInteger();
        public void createQueue(){queue.countDown();await(allowQueue);}
        public boolean register(){registrations.incrementAndGet();registered.countDown();return true;}
        public int nextMessage(){try{return messages.take();}catch(InterruptedException e){return -1;}}
        public void unregister(){unregistered.countDown();}
        public void quit(){quits.incrementAndGet();messages.add(-1);}
        static void await(CountDownLatch latch){try{assertTrue(latch.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}
    }
    @Test void closeBeforeQueueExistsPreventsRegistration(){
        Session s=new Session();DesktopIntegration desktop=new DesktopIntegration(()->s);
        desktop.installHotkey(()->{},message->fail(message));Session.await(s.queue);
        desktop.close();s.allowQueue.countDown();desktop.installHotkey(()->{},message->fail(message));
        assertEquals(0,s.registrations.get());assertEquals(0,s.quits.get());
    }
    @Test void closeUnblocksMessageLoopAndUnregisters(){
        Session s=new Session();s.allowQueue.countDown();DesktopIntegration desktop=new DesktopIntegration(()->s);
        desktop.installHotkey(()->{},message->fail(message));Session.await(s.registered);
        desktop.close();Session.await(s.unregistered);assertEquals(1,s.registrations.get());assertEquals(1,s.quits.get());
    }
}
