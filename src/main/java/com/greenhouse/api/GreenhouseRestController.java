package com.greenhouse.api;

import com.greenhouse.api.dto.EnvironmentRequest;
import com.greenhouse.api.dto.FaultRequest;
import com.greenhouse.api.dto.HeatWaveRequest;
import com.greenhouse.api.dto.ScenarioRequest;
import com.greenhouse.kernel.RealTimeKernel;
import com.greenhouse.monitor.InputDescriber;
import com.greenhouse.monitor.Log;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: Input port of the system (Postman plays the role of the physical world / operator)
 * Purpose: REST endpoints used by the Postman scripts. Each one calls the same kernel operation as
 *          the console menu. Invalid input never reaches the kernel (see GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api")
public class GreenhouseRestController {

    private final RealTimeKernel kernel;

    public GreenhouseRestController(RealTimeKernel kernel) {
        this.kernel = kernel;
    }

    private static final String POSTMAN = "Postman";

    /** Read-only: Postman checks the result of a test step. One short CHECK line, nothing changes. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = kernel.status();
        Log.info("CHECK", "Postman reads the status (test step) -> T=" + s.get("temperature") + " C, vote "
                + s.get("voteStatus") + ", safe state " + (Boolean.TRUE.equals(s.get("safeState")) ? "YES" : "no")
                + ", actuators " + s.get("actuators"));
        return s;
    }

    @PostMapping("/system/reset")
    public Map<String, Object> reset() {
        Log.input(POSTMAN, "POST /api/system/reset", "reset everything to normal (25 C, 60 %, 45 %, no faults)",
                "all faults cleared, actuators OFF, voter back to OK within 1 s");
        kernel.reset();
        return message("System reset to normal state");
    }

    @PostMapping("/environment")
    public Map<String, Object> environment(@Valid @RequestBody EnvironmentRequest req) {
        double t = req.temperature(), h = req.humidity(), s = req.soil();
        Log.input(POSTMAN, "POST /api/environment  {temperature=" + t + ", humidity=" + h + ", soil=" + s + "}",
                InputDescriber.environmentMeaning(t, h, s), InputDescriber.environmentExpected(t, h, s));
        kernel.setEnvironment(t, h, s);
        return message("Environment updated");
    }

    @PostMapping("/environment/heatwave")
    public Map<String, Object> heatWave(@Valid @RequestBody HeatWaveRequest req) {
        Log.input(POSTMAN, "POST /api/environment/heatwave  {delta=" + req.delta() + "}",
                InputDescriber.heatWaveMeaning(req.delta()), InputDescriber.heatWaveExpected());
        kernel.heatWave(req.delta());
        return message("Heat wave applied: " + req.delta() + " C");
    }

    @PostMapping("/sensors/{id}/fault")
    public Map<String, Object> injectFault(@PathVariable String id, @Valid @RequestBody FaultRequest req) {
        kernel.checkFault(id, req.type(), req.value()); // invalid -> REJECTED box instead of an input box
        Log.input(POSTMAN, "POST /api/sensors/" + id + "/fault  {type=" + req.type()
                        + (req.value() == null ? "" : ", value=" + req.value()) + "}",
                InputDescriber.faultMeaning(id, req.type(), req.value()), InputDescriber.faultExpected(id, req.type()));
        kernel.injectFault(id, req.type(), req.value());
        return message("Fault " + req.type() + " injected into " + id.toUpperCase());
    }

    @DeleteMapping("/sensors/{id}/fault")
    public Map<String, Object> repair(@PathVariable String id) {
        kernel.checkSensor(id);
        Log.input(POSTMAN, "DELETE /api/sensors/" + id + "/fault", InputDescriber.sensorName(id) + " is repaired",
                InputDescriber.repairExpected(id));
        kernel.repair(id);
        return message("Sensor " + id.toUpperCase() + " repaired");
    }

    @PostMapping("/system/exception")
    public Map<String, Object> injectException() {
        Log.input(POSTMAN, "POST /api/system/exception", "a software error (exception) will happen in the next control cycle",
                "try/catch -> forward recovery to SAFE STATE, the task keeps running, safe state left after 2 good votes");
        kernel.injectControllerException();
        return message("Exception will be thrown in the next control cycle");
    }

    @PostMapping("/demo/race")
    public Map<String, Object> raceDemo() throws InterruptedException {
        Log.input(POSTMAN, "POST /api/demo/race", "race-condition demo: 2 threads x 100 000 increments",
                "unsafe counter LOSES updates, synchronized and AtomicInteger = 200 000");
        return kernel.runRaceDemo();
    }

    @GetMapping("/timing")
    public Map<String, Object> timing() {
        Log.input(POSTMAN, "GET /api/timing", "show the timing analysis table",
                "max C per task, U = C/T, total U compared with the RM bound 0.757");
        kernel.printTimingReport("requested by Postman");
        return kernel.timingSnapshot();
    }

    @PostMapping("/scenario/start")
    public Map<String, Object> scenarioStart(@Valid @RequestBody ScenarioRequest req) {
        kernel.scenarioStart(req.name(), req.expect(),
                "Postman:  POST /api/scenario/start  {name=" + req.name() + "}");
        return message("Scenario started: " + req.name());
    }

    @PostMapping("/scenario/end")
    public Map<String, Object> scenarioEnd() {
        return kernel.scenarioEnd("Postman:  POST /api/scenario/end");
    }

    @PostMapping("/system/shutdown")
    public Map<String, Object> shutdown() {
        Log.input(POSTMAN, "POST /api/system/shutdown", "stop the system",
                "stop all periodic tasks, actuators to safe position, final timing report, clean exit");
        kernel.requestShutdown("REST API");
        return message("Shutting down - final timing report is printed in the CLI");
    }

    private static Map<String, Object> message(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", text);
        m.put("timeMs", System.currentTimeMillis());
        return m;
    }
}
