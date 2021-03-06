package org.openlumify.core.ingest.graphProperty;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import org.vertexium.Element;
import org.openlumify.core.exception.OpenLumifyException;
import org.openlumify.core.status.MetricsManager;
import org.openlumify.core.status.PausableTimerContext;
import org.openlumify.core.status.PausableTimerContextAware;
import org.openlumify.core.trace.Trace;
import org.openlumify.core.trace.TraceSpan;
import org.openlumify.core.util.OpenLumifyLogger;
import org.openlumify.core.util.OpenLumifyLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class GraphPropertyThreadedWrapper implements Runnable {
    private static final OpenLumifyLogger LOGGER = OpenLumifyLoggerFactory.getLogger(GraphPropertyThreadedWrapper.class);
    private static final int DEQUEUE_TIMEOUT_MS = 30 * 1000;
    private static final int DEQUEUE_LOG_MESSAGE_FREQUENCY_MS = 10 * 1000;
    private static final int DEQUEUE_WARN_THRESHOLD_MS = 30 * 1000;
    private final GraphPropertyWorker worker;

    public GraphPropertyThreadedWrapper(GraphPropertyWorker worker) {
        this.worker = worker;
    }

    private Counter totalProcessedCounter = null;
    private Counter processingCounter;
    private Counter totalErrorCounter;
    private Timer processingTimeTimer;
    private volatile boolean stopped;
    private final Queue<Work> workItems = new LinkedList<>();
    private final Queue<WorkResult> workResults = new LinkedList<>();
    private MetricsManager metricsManager;

    @Override
    public final void run() {
        ensureMetricsInitialized();

        stopped = false;
        try {
            while (!stopped) {
                Work work;
                synchronized (workItems) {
                    if (workItems.size() == 0) {
                        workItems.wait(1000);
                        continue;
                    }
                    work = workItems.remove();
                }
                InputStream in = work.getIn();
                String workerClassName = this.worker.getClass().getName();
                Element element = work.getData() == null ? null : work.getData().getElement();
                String elementId = element == null ? null : element.getId();
                try {
                    LOGGER.debug("BEGIN doWork (%s): %s", workerClassName, elementId);
                    PausableTimerContext timerContext = new PausableTimerContext(processingTimeTimer);
                    if (in instanceof PausableTimerContextAware) {
                        ((PausableTimerContextAware) in).setPausableTimerContext(timerContext);
                    }
                    processingCounter.inc();
                    long startTime = System.currentTimeMillis();
                    TraceSpan traceSpan = startTraceIfEnabled(work, elementId);
                    try {
                        this.worker.execute(in, work.getData());
                    } finally {
                        stopTraceIfEnabled(work, traceSpan);
                        long endTime = System.currentTimeMillis();
                        long time = endTime - startTime;
                        LOGGER.debug("END doWork (%s): %s (%dms)", workerClassName, elementId, time);
                        processingCounter.dec();
                        totalProcessedCounter.inc();
                        timerContext.stop();
                    }
                    synchronized (workResults) {
                        workResults.add(new WorkResult(null));
                        workResults.notifyAll();
                    }
                } catch (Throwable ex) {
                    LOGGER.error("failed to complete work (%s): %s", workerClassName, elementId, ex);
                    totalErrorCounter.inc();
                    synchronized (workResults) {
                        workResults.add(new WorkResult(ex));
                        workResults.notifyAll();
                    }
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (IOException ex) {
                        synchronized (workResults) {
                            workResults.add(new WorkResult(ex));
                            workResults.notifyAll();
                        }
                    }
                }
            }
        } catch (InterruptedException ex) {
            LOGGER.error("thread was interrupted", ex);
        }
    }

    private void stopTraceIfEnabled(Work work, TraceSpan traceSpan) {
        if (work.getData().isTraceEnabled()) {
            if (traceSpan != null) {
                traceSpan.close();
            }
            Trace.off();
        }
    }

    private TraceSpan startTraceIfEnabled(Work work, String elementId) {
        TraceSpan traceSpan = null;
        if (work.getData().isTraceEnabled()) {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("elementId", elementId);
            traceSpan = Trace.on("GPW: " + this.worker.getClass().getName(), parameters);
        }
        return traceSpan;
    }

    private void ensureMetricsInitialized() {
        if (totalProcessedCounter == null) {
            totalProcessedCounter = metricsManager.counter(this.worker, "total-processed");
            processingCounter = metricsManager.counter(this.worker, "processing");
            totalErrorCounter = metricsManager.counter(this.worker, "total-errors");
            processingTimeTimer = metricsManager.timer(this.worker, "processing-time");
        }
    }

    public void enqueueWork(InputStream in, GraphPropertyWorkData data) {
        synchronized (workItems) {
            workItems.add(new Work(in, data));
            workItems.notifyAll();
        }
    }

    public WorkResult dequeueResult(boolean waitForever) {
        synchronized (workResults) {
            if (workResults.size() == 0) {
                Date startTime = new Date();
                Date lastMessageTime = new Date();
                while (workResults.size() == 0 && (waitForever || (getElapsedTime(startTime) < DEQUEUE_TIMEOUT_MS))) {
                    try {
                        if (getElapsedTime(lastMessageTime) > DEQUEUE_LOG_MESSAGE_FREQUENCY_MS) {
                            String message = String.format(
                                    "Worker \"%s\" has zero results. Waiting for results. (startTime: %s, elapsedTime: %ds, thread: %s)",
                                    worker.getClass().getName(),
                                    startTime,
                                    getElapsedTime(startTime) / 1000,
                                    Thread.currentThread().getName()
                            );
                            if (getElapsedTime(startTime) > DEQUEUE_WARN_THRESHOLD_MS) {
                                LOGGER.warn("%s", message);
                            } else {
                                LOGGER.debug("%s", message);
                            }
                            lastMessageTime = new Date();
                        }
                        workResults.wait(1000);
                    } catch (InterruptedException ex) {
                        throw new OpenLumifyException("Failed to wait for worker " + worker.getClass().getName(), ex);
                    }
                }
            }
            return workResults.remove();
        }
    }

    private long getElapsedTime(Date date) {
        return new Date().getTime() - date.getTime();
    }

    public void stop() {
        stopped = true;
    }

    public GraphPropertyWorker getWorker() {
        return worker;
    }

    private class Work {
        private final InputStream in;
        private final GraphPropertyWorkData data;

        public Work(InputStream in, GraphPropertyWorkData data) {
            this.in = in;
            this.data = data;
        }

        private InputStream getIn() {
            return in;
        }

        private GraphPropertyWorkData getData() {
            return data;
        }
    }

    public static class WorkResult {
        private final Throwable error;

        public WorkResult(Throwable error) {
            this.error = error;
        }

        public Throwable getError() {
            return error;
        }
    }

    @Inject
    public void setMetricsManager(MetricsManager metricsManager) {
        this.metricsManager = metricsManager;
    }

    @Override
    public String toString() {
        return "GraphPropertyThreadedWrapper{" +
                "worker=" + worker +
                '}';
    }
}
