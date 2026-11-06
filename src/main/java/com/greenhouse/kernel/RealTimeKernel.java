package com.greenhouse.kernel;

import com.greenhouse.buffer.BoundedBuffer;
import com.greenhouse.buffer.LatestReadings;
import com.greenhouse.buffer.ReadingConsumer;
import com.greenhouse.control.Actuators;
import com.greenhouse.control.Controller;
import com.greenhouse.demo.RaceConditionDemo;
import com.greenhouse.fault.ReadingValidator;
import com.greenhouse.fault.SafeStateManager;
import com.greenhouse.fault.TmrVoter;
import com.greenhouse.fault.VoteResult;
import com.greenhouse.fault.Watchdog;
import com.greenhouse.monitor.EventRecorder;
import com.greenhouse.monitor.FaultMenu;
import com.greenhouse.monitor.Log;
import com.greenhouse.monitor.StatusDisplay;
import com.greenhouse.monitor.TimingMonitor;
import com.greenhouse.sim.FaultMode;
import com.greenhouse.sim.GreenhouseSimulator;
import com.greenhouse.sim.Reading;
import com.greenhouse.sim.Sensor;
import com.greenhouse.sim.SensorType;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #6 Threads, Runnable, ScheduledExecutorService (fixed rate), interrupt and clean shutdown,
 *           #1 periodic tasks with their periods, #7 AtomicBoolean (shutdown only once)
 * Purpose: Builds all parts of the system, starts every periodic task with scheduleAtFixedRate,
 *          starts the consumer thread and the console menu, offers the operations used by the
 *          REST API and the menu, and shuts everything down cleanly.
 */
@Component
public class RealTimeKernel {

    public static final List<String> TEMPERATURE_IDS = List.of("T1", "T2", "T3");
    public static final String HUMIDITY_ID = "H1";
    public static final String SOIL_ID = "S1";
    private static final int BUFFER_SIZE = 10;
    private static final long TIMING_REPORT_PERIOD_MS = 60_000;

    private final ApplicationContext context;
    private final boolean menuEnabled;
    private final int serverPort;

    private final GreenhouseSimulator simulator = new GreenhouseSimulator();
    private final BoundedBuffer buffer = new BoundedBuffer(BUFFER_SIZE);
    private final TimingMonitor timing = new TimingMonitor();
    private final LatestReadings latest = new LatestReadings();
    private final Actuators actuators = new Actuators();
    private final Map<String, Sensor> sensors = new LinkedHashMap<>();
    private final Watchdog watchdog;
    private final SafeStateManager safeState;
    private final Controller controller;
    private final ReadingConsumer consumer;
    private final StatusDisplay display;
    private final RaceConditionDemo raceDemo = new RaceConditionDemo();

    private ScheduledExecutorService executor;
    private Thread consumerThread;
    private final AtomicBoolean stopped = new AtomicBoolean(false); // CONCEPT 7
    private final long startedAtMs = System.currentTimeMillis();

    public RealTimeKernel(ApplicationContext context, @Value("${greenhouse.menu.enabled:true}") boolean menuEnabled,
                          @Value("${server.port:8080}") int serverPort) {
        this.context = context;
        this.menuEnabled = menuEnabled;
        this.serverPort = serverPort;

        for (String id : TEMPERATURE_IDS) {
            sensors.put(id, new Sensor(id, SensorType.TEMPERATURE, simulator, buffer, timing));
        }
        sensors.put(HUMIDITY_ID, new Sensor(HUMIDITY_ID, SensorType.HUMIDITY, simulator, buffer, timing));
        sensors.put(SOIL_ID, new Sensor(SOIL_ID, SensorType.SOIL, simulator, buffer, timing));

        watchdog = new Watchdog(sensors.values(), latest, timing);
        safeState = new SafeStateManager(actuators);
        controller = new Controller(TEMPERATURE_IDS, HUMIDITY_ID, SOIL_ID, latest, watchdog, new TmrVoter(),
                safeState, actuators, simulator, timing);
        consumer = new ReadingConsumer(buffer, latest, new ReadingValidator(), controller);
        display = new StatusDisplay(controller, actuators, safeState, watchdog, sensors.values(), buffer, timing);
    }

    // ---------------------------------------------------------------- start / stop

    /** Started when the web server is ready, so Postman can connect immediately. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Log.info("KERNEL", "Smart Greenhouse Controller starting ...");
        primeReadings();

        // CONCEPT 6: one ScheduledExecutorService runs all periodic Runnables at a FIXED RATE
        AtomicInteger threadNo = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(8, r -> new Thread(r, "rt-worker-" + threadNo.incrementAndGet()));

        for (Sensor s : sensors.values()) {
            executor.scheduleAtFixedRate(named("sensor-" + s.getId(), s), 0, Sensor.PERIOD_MS, TimeUnit.MILLISECONDS);
        }
        executor.scheduleAtFixedRate(named("controller", controller), Controller.PERIOD_MS, Controller.PERIOD_MS, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(named("watchdog", watchdog), Watchdog.PERIOD_MS, Watchdog.PERIOD_MS, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(named("display", display), StatusDisplay.PERIOD_MS, StatusDisplay.PERIOD_MS, TimeUnit.MILLISECONDS);
        // the "physical world" (not part of the controller's task set, so not in the timing table)
        executor.scheduleAtFixedRate(named("plant", this::plantStep), 1000, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(named("timing", () -> timing.printReport("periodic")),
                TIMING_REPORT_PERIOD_MS, TIMING_REPORT_PERIOD_MS, TimeUnit.MILLISECONDS);

        // CONCEPT 6: the consumer is a normal Thread created from a Runnable
        consumerThread = new Thread(consumer, "consumer");
        consumerThread.start();

        Log.info("KERNEL", "Started: 5 sensors every " + Sensor.PERIOD_MS + " ms, controller every " + Controller.PERIOD_MS
                + " ms, watchdog every " + Watchdog.PERIOD_MS + " ms, status every " + StatusDisplay.PERIOD_MS + " ms");
        Log.info("KERNEL", "REST API for Postman: http://localhost:" + serverPort + "/api/status");

        Log.howToRead();
        if (menuEnabled) {
            Thread menu = new Thread(new FaultMenu(this), "fault-menu");
            menu.setDaemon(true);
            menu.start();
        }
    }

    /** Gives the running thread a readable name in the log (e.g. "sensor-T1"). */
    private static Runnable named(String name, Runnable task) {
        return () -> {
            Thread.currentThread().setName(name);
            task.run();
        };
    }

    private void plantStep() {
        try {
            simulator.step(actuators.heater.isOn(), actuators.fan.isOn(), actuators.pump.isOn());
        } catch (Exception e) {
            Log.error("SIMULATOR", "Plant simulation step failed - continues", e);
        }
    }

    /** CONCEPT 6: clean shutdown - stop periodic tasks, interrupt the consumer, wait for the threads. */
    @PreDestroy
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        Log.warn("KERNEL", "Shutting down ...");
        if (executor != null) {
            executor.shutdown(); // no new periodic releases
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // interrupts tasks that are still running
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        safeState.applyForShutdown();
        if (consumerThread != null) {
            consumerThread.interrupt(); // wakes the consumer out of wait() -> InterruptedException -> loop ends
            try {
                consumerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        timing.printReport("FINAL");
        Log.info("KERNEL", "Clean shutdown complete - all real-time threads stopped");
        Log.close();
    }

    /** Shutdown requested by REST or menu: answer first, then exit the whole application. */
    public void requestShutdown(String source) {
        Log.warn("KERNEL", "Shutdown requested via " + source);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300); // let the HTTP response reach Postman
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int code = SpringApplication.exit(context, () -> 0); // calls stop() via @PreDestroy
            System.exit(code);
        }, "shutdown");
        t.start();
    }

    // ---------------------------------------------------------------- operations (REST + menu)

    public void reset() {
        for (Sensor s : sensors.values()) s.repair();
        simulator.reset();
        buffer.clear();
        watchdog.reset();
        controller.reset();
        primeReadings();
        Log.info("SYSTEM", "RESET - environment normal (25 C, 60 %, 45 %), all faults cleared, actuators OFF");
    }

    /** Fresh readings for every sensor so the voter has data immediately (start-up / reset). */
    private void primeReadings() {
        long now = System.currentTimeMillis();
        latest.resetAll(sensors.keySet(), now);
        for (Sensor s : sensors.values()) {
            latest.accept(new Reading(s.getId(), s.getType(), simulator.read(s.getType()), now));
        }
    }

    public void setEnvironment(double temperature, double humidity, double soil) {
        simulator.setEnvironment(temperature, humidity, soil);
    }

    public void heatWave(double delta) {
        simulator.addHeatWave(delta);
    }

    /** Throws if the fault request is invalid (unknown sensor / missing value) - checked BEFORE anything changes. */
    public void checkFault(String sensorId, FaultMode mode, Double value) {
        sensor(sensorId);
        if (mode == FaultMode.WRONG_VALUE && value == null) {
            throw new IllegalArgumentException("Fault WRONG_VALUE needs a 'value' (offset, e.g. 15)");
        }
    }

    /** Throws UnknownSensorException if the sensor does not exist. */
    public void checkSensor(String sensorId) {
        sensor(sensorId);
    }

    public void injectFault(String sensorId, FaultMode mode, Double value) {
        checkFault(sensorId, mode, value);
        sensor(sensorId).injectFault(mode, value == null ? 0 : value);
    }

    public void repair(String sensorId) {
        Sensor s = sensor(sensorId);
        if (s.getFaultMode() == FaultMode.NONE) {
            Log.info("FAULT", sensorId + " has no fault - nothing to repair");
        }
        s.repair();
    }

    public void repairAll() {
        Log.info("FAULT", "Repairing all sensors");
        for (Sensor s : sensors.values()) s.repair();
    }

    public void injectControllerException() {
        controller.injectException();
    }

    public Map<String, Object> runRaceDemo() throws InterruptedException {
        return raceDemo.run();
    }

    public void printStatusNow() {
        display.printNow();
    }

    /**
     * Prints a big banner so everybody can see which scenario is running now.
     * @param source which input caused it, e.g. "Postman:  POST /api/scenario/start"
     */
    public void scenarioStart(String name, String expected, String source) {
        EventRecorder.start(name, expected);
        Log.banner(name, expected == null || expected.isBlank() ? "-" : expected, source);
    }

    /** Prints a numbered summary of the important events since the scenario started. */
    public Map<String, Object> scenarioEnd(String source) {
        String name = EventRecorder.scenario();
        long duration = System.currentTimeMillis() - EventRecorder.startedAtMs();
        List<String> events = EventRecorder.events();
        Log.summary(name, duration, events, EventRecorder.droppedEvents(), source);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", name);
        m.put("expected", EventRecorder.expected());
        m.put("durationMs", duration);
        m.put("eventCount", events.size());
        m.put("events", events);
        m.put("finalStatus", status());
        return m;
    }

    public void printTimingReport(String title) {
        timing.printReport(title);
    }

    public Map<String, Object> timingSnapshot() {
        return timing.snapshot();
    }

    private Sensor sensor(String id) {
        Sensor s = id == null ? null : sensors.get(id.trim().toUpperCase());
        if (s == null) throw new UnknownSensorException(id);
        return s;
    }

    /** Complete system status as JSON-friendly map (GET /api/status). */
    public Map<String, Object> status() {
        VoteResult v = controller.getLastVote();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSeconds", (System.currentTimeMillis() - startedAtMs) / 1000);
        m.put("temperature", v.trustworthy() ? round1(v.value()) : null);
        m.put("voteStatus", v.status().name());
        m.put("sensorsUsed", v.sensorsUsed());
        m.put("voteDetail", v.detail());
        m.put("humidity", controller.getLastHumidity() == null ? null : round1(controller.getLastHumidity()));
        m.put("soil", controller.getLastSoil() == null ? null : round1(controller.getLastSoil()));
        m.put("actuators", actuators.toMap());
        m.put("safeState", safeState.isActive());
        m.put("safeStateReason", safeState.getReason());
        m.put("lastSafeStateReason", safeState.getLastReason());
        m.put("safeStateCount", safeState.getEnterCount());

        Map<String, Object> sensorInfo = new LinkedHashMap<>();
        for (Sensor s : sensors.values()) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("health", watchdog.isHealthy(s.getId()) ? "OK" : "FAILED");
            si.put("fault", s.getFaultMode().name());
            double last = s.getLastProduced();
            si.put("lastValue", Double.isNaN(last) ? null : round1(last));
            sensorInfo.put(s.getId(), si);
        }
        m.put("sensors", sensorInfo);

        long rt2 = controller.getLastRt2ReactionMs();
        m.put("rt2ReactionMs", rt2 < 0 ? null : rt2);
        m.put("detectionMs", watchdog.getDetectionLatencies());

        Map<String, Object> buf = new LinkedHashMap<>();
        buf.put("size", buffer.size());
        buf.put("capacity", buffer.capacity());
        buf.put("dropped", buffer.getDropped());
        m.put("buffer", buf);
        m.put("trueEnvironment", simulator.snapshot());
        m.put("deadlineMisses", timing.getDeadlineMisses());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
