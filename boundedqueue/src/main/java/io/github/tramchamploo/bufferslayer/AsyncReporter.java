package io.github.tramchamploo.bufferslayer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.github.tramchamploo.bufferslayer.MessageDroppedException.dropped;
import static java.util.Collections.singletonList;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.tramchamploo.bufferslayer.Message.MessageKey;
import io.github.tramchamploo.bufferslayer.OverflowStrategy.Strategy;
import io.github.tramchamploo.bufferslayer.QueueManager.CreateCallback;
import io.github.tramchamploo.bufferslayer.internal.CompositeFuture;
import io.github.tramchamploo.bufferslayer.internal.Future;
import io.github.tramchamploo.bufferslayer.internal.FutureListener;
import io.github.tramchamploo.bufferslayer.internal.MessageFuture;
import io.github.tramchamploo.bufferslayer.internal.MessagePromise;
import io.github.tramchamploo.bufferslayer.internal.SucceededFuture;
import java.io.Flushable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncReporter<M extends Message, R> extends TimeDriven<MessageKey> implements Reporter<M, R>, Flushable {

  static final Logger logger = LoggerFactory.getLogger(AsyncReporter.class);

  private static final int MAX_BUFFER_POOL_ENTRIES = 1000;

  static AtomicLong idGenerator = new AtomicLong();
  final Long id = idGenerator.getAndIncrement();
  final AsyncSender<R> sender;

  final long messageTimeoutNanos;
  final int bufferedMaxMessages;
  private final ReporterMetrics metrics;
  private final boolean singleKey;

  private final int timerThreads;
  private final int flushThreads;
  private final FlushThreadFactory flushThreadFactory;
  Set<Thread> flushers;

  private static HashedWheelTimer hashedWheelTimer;
  private static ThreadFactory hashedWheelTimerThreadFactory;

  final FlushSynchronizer synchronizer = new FlushSynchronizer();
  final QueueManager queueManager;

  public static final int REPORTER_STATE_INIT = 0;
  public static final int REPORTER_STATE_STARTED = 1;
  public static final int REPORTER_STATE_SHUTDOWN = 2;
  @SuppressWarnings({"unused", "FieldMayBeFinal"})
  private volatile int reporterState; // 0 - init, 1 - started, 2 - shut down

  static final AtomicIntegerFieldUpdater<AsyncReporter> REPORTER_STATE_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(AsyncReporter.class, "reporterState");

  CountDownLatch close;

  ScheduledExecutorService scheduler;
  private BufferPool bufferPool;
  private final MemoryLimiter memoryLimiter;

  static {
    ThreadFactoryBuilder builder = new ThreadFactoryBuilder()
        .setNameFormat(AsyncReporter.class.getSimpleName() + "-cleaner")
        .setDaemon(true);
    hashedWheelTimerThreadFactory = builder.build();
  }

  AsyncReporter(Builder<M, R> builder) {
    this.sender = toAsyncSender(builder);
    this.metrics = builder.metrics;
    this.memoryLimiter = MemoryLimiter.maxOf(builder.totalQueuedMessages, metrics);
    this.messageTimeoutNanos = builder.messageTimeoutNanos;
    this.bufferedMaxMessages = builder.bufferedMaxMessages;
    this.singleKey = builder.singleKey;

    this.flushThreads = builder.flushThreads;
    this.timerThreads = builder.timerThreads;

    this.queueManager =
        new QueueManager(builder.pendingMaxMessages, builder.overflowStrategy,
            builder.pendingKeepaliveNanos, this,
            metrics, initHashedWheelTimer(builder));

    this.flushThreadFactory = new FlushThreadFactory(this);
    this.bufferPool = new BufferPool(MAX_BUFFER_POOL_ENTRIES,
        builder.bufferedMaxMessages, builder.singleKey);

    if (messageTimeoutNanos > 0) {
      this.queueManager.onCreate(new CreateCallback() {
        @Override
        public void call(AbstractSizeBoundedQueue queue) {
          schedulePeriodically(queue.key, messageTimeoutNanos);
        }
      });
    }
  }

  private static HashedWheelTimer initHashedWheelTimer(Builder<?, ?> builder) {
    synchronized (AsyncReporter.class) {
      if (hashedWheelTimer == null) {
        hashedWheelTimer = new HashedWheelTimer(
            hashedWheelTimerThreadFactory,
            builder.tickDurationNanos, TimeUnit.NANOSECONDS,
            builder.ticksPerWheel);
      }
    }
    return hashedWheelTimer;
  }

  AsyncSender<R> toAsyncSender(Builder<M, R> builder) {
    return new AsyncSenderAdaptor<>(builder.sender, builder.sharedSenderThreads);
  }

  public static <M extends Message, R> Builder<M, R> builder(Sender<M, R> sender) {
    return new Builder<>(sender);
  }

  public static final class Builder<M extends Message, R> extends Reporter.Builder<M, R> {

    int sharedSenderThreads = 1;
    int flushThreads = AsyncReporterProperties.DEFAULT_FLUSH_THREADS;
    int timerThreads = AsyncReporterProperties.DEFAULT_TIMER_THREADS;
    long pendingKeepaliveNanos = TimeUnit.SECONDS.toNanos(60);
    long tickDurationNanos = TimeUnit.MILLISECONDS.toNanos(100);
    int ticksPerWheel = 512;
    boolean singleKey = false;
    int totalQueuedMessages = 100_000;

    Builder(Sender<M, R> sender) {
      super(sender);
    }

    @Override
    public Builder<M, R> metrics(ReporterMetrics metrics) {
      super.metrics(metrics);
      return this;
    }

    @Override
    public Builder<M, R> messageTimeout(long timeout, TimeUnit unit) {
      super.messageTimeout(timeout, unit);
      return this;
    }

    @Override
    public Builder<M, R> bufferedMaxMessages(int bufferedMaxMessages) {
      super.bufferedMaxMessages(bufferedMaxMessages);
      return this;
    }

    @Override
    public Builder<M, R> pendingMaxMessages(int pendingMaxMessages) {
      super.pendingMaxMessages(pendingMaxMessages);
      return this;
    }

    @Override
    public Builder<M, R> overflowStrategy(Strategy overflowStrategy) {
      super.overflowStrategy(overflowStrategy);
      return this;
    }

    public Builder<M, R> sharedSenderThreads(int senderThreads) {
      checkArgument(senderThreads > 0, "sharedSenderThreads > 0: %s", senderThreads);
      this.sharedSenderThreads = senderThreads;
      return this;
    }

    public Builder<M, R> flushThreads(int flushThreads) {
      checkArgument(flushThreads > 0, "flushThreads > 0: %s", flushThreads);
      this.flushThreads = flushThreads;
      return this;
    }

    public Builder<M, R> timerThreads(int timerThreads) {
      checkArgument(timerThreads > 0, "timerThreads > 0: %s", timerThreads);
      this.timerThreads = timerThreads;
      return this;
    }

    public Builder<M, R> pendingKeepalive(long keepalive, TimeUnit unit) {
      checkArgument(keepalive > 0, "keepalive > 0: %s", keepalive);
      this.pendingKeepaliveNanos = unit.toNanos(keepalive);
      return this;
    }

    public Builder<M, R> tickDuration(long tickDuration, TimeUnit unit) {
      checkArgument(tickDuration > 0, "tickDuration > 0: %s", tickDuration);
      this.tickDurationNanos = unit.toNanos(tickDuration);
      return this;
    }

    public Builder<M, R> ticksPerWheel(int ticksPerWheel) {
      checkArgument(ticksPerWheel > 0, "ticksPerWheel > 0: %s", ticksPerWheel);
      this.ticksPerWheel = ticksPerWheel;
      return this;
    }

    public Builder<M, R> singleKey(boolean singleKey) {
      this.singleKey = singleKey;
      return this;
    }

    public Builder<M, R> totalQueuedMessages(int totalQueuedMessages) {
      checkArgument(totalQueuedMessages >= 0, "totalQueuedMessages >= 0: %s", totalQueuedMessages);
      this.totalQueuedMessages = totalQueuedMessages;
      return this;
    }

    public AsyncReporter<M, R> build() {
      checkArgument(totalQueuedMessages >= pendingMaxMessages, "totalQueuedMessages >= pendingMaxMessages: %s", totalQueuedMessages);
      return new AsyncReporter<>(this);
    }
  }

  @Override
  protected void onTimer(MessageKey timerKey) {
    AbstractSizeBoundedQueue q = queueManager.get(timerKey);
    if (q != null && q.size() > 0) flush(q);
  }

  @Override
  protected ScheduledExecutorService scheduler() {
    if (this.scheduler == null) {
      synchronized (this) {
        if (this.scheduler == null) {
          ThreadFactory timerFactory = new ThreadFactoryBuilder()
              .setNameFormat("AsyncReporter-" + id + "-timer-%d")
              .setDaemon(true)
              .build();
          ScheduledThreadPoolExecutor timerPool =
              new ScheduledThreadPoolExecutor(timerThreads, timerFactory);
          timerPool.setRemoveOnCancelPolicy(true);
          this.scheduler = timerPool;
          return timerPool;
        }
      }
    }
    return scheduler;
  }

  @Override
  @SuppressWarnings("unchecked")
  public MessageFuture<R> report(M message) {
    checkNotNull(message, "message");
    metrics.incrementMessages(1);

    if (REPORTER_STATE_UPDATER.get(this) == REPORTER_STATE_SHUTDOWN) { // Drop the message when closed.
      MessageDroppedException cause =
          dropped(new IllegalStateException("closed!"), singletonList(message));
      MessageFuture<R> future = (MessageFuture<R>) message.newFailedFuture(cause);
      setFailListener(future);
      return future;
    }

    // Lazy initialize flush threads
    if (REPORTER_STATE_UPDATER.compareAndSet(this, REPORTER_STATE_INIT, REPORTER_STATE_STARTED) &&
        messageTimeoutNanos > 0) {
      startFlushThreads();
    }

    memoryLimiter.waitWhenMaximum();
    // If singleKey is true, ignore original message key.
    Message.MessageKey key = singleKey ?
        Message.SINGLE_KEY : message.asMessageKey();

    // Offer message to pending queue.
    AbstractSizeBoundedQueue pending = queueManager.getOrCreate(key);
    MessagePromise<R> promise = message.newPromise();
    pending.offer(promise);
    setFailListener(promise);

    if (pending.size() >= bufferedMaxMessages)
      synchronizer.offer(pending);
    return promise;
  }

  private void startFlushThreads() {
    Set<Thread> flushers = new HashSet<>(flushThreads);
    for (int i = 0; i < flushThreads; i++) {
      Thread thread = flushThreadFactory.newFlushThread();
      thread.start();
      flushers.add(thread);
    }
    this.flushers = Collections.unmodifiableSet(flushers);
  }

  /**
   * Update metrics if reporting failed.
   */
  private void setFailListener(MessageFuture<R> future) {
    future.addListener(new FutureListener<R>() {

      @Override
      public void operationComplete(Future<R> future) {
        if (!future.isSuccess()) {
          metrics.incrementMessagesDropped(1);
        }
      }
    });
  }

  @Override
  public void flush() {
    for (AbstractSizeBoundedQueue pending : queueManager.elements()) {
      Future<?> future = flush(pending);
      future.awaitUninterruptibly();
    }
  }

  Future<?> flush(AbstractSizeBoundedQueue pending) {
    CompositeFuture result;
    Buffer buffer = bufferPool.acquire();
    try {
      int drained = pending.drainTo(buffer);
      if (drained == 0) {
        return new SucceededFuture<>(null, null);
      }

      List<MessagePromise<R>> promises = buffer.drain();
      result = sender.send(promises);
    } finally {
      bufferPool.release(buffer);
    }
    // Update metrics
    metrics.updateQueuedMessages(pending.key, pending.size());
    // Signal producers
    if (!memoryLimiter.isMaximum())
      memoryLimiter.signalAll();

    logWhenFailed(result);
    return result;
  }

  /**
   * Log errors only when sending fails.
   */
  private void logWhenFailed(CompositeFuture future) {
    future.addListener(new FutureListener<CompositeFuture>() {

      @Override
      public void operationComplete(Future<CompositeFuture> future) {
        if (!future.isSuccess()) {
          MessageDroppedException ex = (MessageDroppedException) future.cause();
          logger.warn(ex.getMessage());
        }
      }
    });
  }

  @Override
  public CheckResult check() {
    return sender.check();
  }

  @Override
  public void close() throws IOException {
    if (!REPORTER_STATE_UPDATER.compareAndSet(this, REPORTER_STATE_STARTED, REPORTER_STATE_SHUTDOWN)) {
      // reporterState can be 0 or 2 at this moment - let it always be 2.
      if (REPORTER_STATE_UPDATER.getAndSet(this, REPORTER_STATE_SHUTDOWN) != REPORTER_STATE_SHUTDOWN) {
        sender.close();
        return;
      }
    }

    flush();

    close = new CountDownLatch(messageTimeoutNanos > 0 ? flushThreads : 0);
    try {
      if (!close.await(messageTimeoutNanos * 2, TimeUnit.NANOSECONDS)) {
        logger.warn("Timed out waiting for close");
      }
    } catch (InterruptedException e) {
      logger.warn("Interrupted waiting for close");
      Thread.currentThread().interrupt();
    }

    if (scheduler != null) {
      clearTimers();
      scheduler.shutdown();
    }
    sender.close();

    int dropped = clearPendings();
    if (dropped > 0) {
      logger.warn("Dropped " + dropped + " messages due to AsyncReporter.close()");
    }
  }

  int clearPendings() {
    int count = 0;
    for (AbstractSizeBoundedQueue q : queueManager.elements()) {
      count += q.clear();
      metrics.removeFromQueuedMessages(q.key);
    }
    queueManager.clear();
    if (count > 0) {
      metrics.incrementMessagesDropped(count);
    }
    return count;
  }
}
