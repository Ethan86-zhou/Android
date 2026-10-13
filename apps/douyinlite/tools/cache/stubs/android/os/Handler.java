package android.os;
import java.util.ArrayList;
import java.util.List;
public final class Handler {
    private record Task(Handler owner,Runnable work,long when) { }
    private static final List<Task> QUEUE=new ArrayList<>();
    public Handler(Looper looper) { }
    public boolean post(Runnable work) { return postDelayed(work,0); }
    public boolean postDelayed(Runnable work,long delay) {
        synchronized(QUEUE) { QUEUE.add(new Task(this,work,SystemClock.uptimeMillis()+delay)); }
        return true;
    }
    public void removeCallbacks(Runnable work) {
        synchronized(QUEUE) { QUEUE.removeIf(task->task.owner==this && task.work==work); }
    }
    public void removeCallbacksAndMessages(Object token) {
        synchronized(QUEUE) { QUEUE.removeIf(task->task.owner==this); }
    }
    public static void drainDue() {
        for (int count=0;count<1000;count++) {
            Task next=null;
            synchronized(QUEUE) {
                for(Task task:QUEUE) if(task.when<=SystemClock.uptimeMillis() && (next==null || task.when<next.when)) next=task;
                if(next!=null) QUEUE.remove(next);
            }
            if(next==null) return;
            next.work.run();
        }
        throw new AssertionError("runaway handler schedule");
    }
}
