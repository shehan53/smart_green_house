package com.greenhouse.monitor;

import com.greenhouse.kernel.RealTimeKernel;
import com.greenhouse.sim.FaultMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #6 Runnable in its own (daemon) thread, #5 invalid input handled without crashing
 * Purpose: Console menu to trigger the fault scenarios F1-F5 without Postman.
 *          Every choice calls the same kernel operations as the REST API.
 */
public class FaultMenu implements Runnable {

    private final RealTimeKernel kernel;

    public FaultMenu(RealTimeKernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void run() {
        printMenu();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (!Thread.currentThread().isInterrupted()) {
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                Log.warn("MENU", "Console input not available - menu disabled (REST API still works)");
                return;
            }
            if (line == null) {
                Log.info("MENU", "No console input (stdin closed) - menu disabled (REST API still works)");
                return;
            }
            try {
                if (!handle(line.trim())) return;
            } catch (Exception e) {
                Log.error("MENU", "Menu action failed - system keeps running", e);
            }
        }
    }

    /** @return false when the menu should stop (exit chosen) */
    private boolean handle(String choice) throws InterruptedException {
        switch (choice.toLowerCase()) {
            case "" -> { }
            case "1" -> {
                input("1", "F1 - T2 wrong value (+15 C)",
                        InputDescriber.faultMeaning("T2", FaultMode.WRONG_VALUE, 15.0),
                        "voter out-votes T2 -> MASKED, no false alarm  (type s for the summary, 6 to repair)");
                kernel.injectFault("T2", FaultMode.WRONG_VALUE, 15.0);
            }
            case "2" -> {
                input("2", "F2 - T3 dead", InputDescriber.faultMeaning("T3", FaultMode.DEAD, null),
                        "watchdog marks T3 FAILED within 2.5 s -> voter DEGRADED  (type s for the summary, 6 to repair)");
                kernel.injectFault("T3", FaultMode.DEAD, null);
            }
            case "3" -> {
                input("3", "F3 - T3 dead + T1 wrong value", "temperature sensor T3 is DEAD and T1 reports +15 C too much",
                        "only 2 sensors left and they disagree -> SAFE STATE (heater OFF, fan ON, alarm ON); "
                                + "press 6 to repair -> safe state left after 2 good votes, then s");
                kernel.injectFault("T3", FaultMode.DEAD, null);
                kernel.injectFault("T1", FaultMode.WRONG_VALUE, 15.0);
            }
            case "4" -> {
                input("4", "F4 - race-condition demo", "2 threads x 100 000 increments on 3 counters",
                        "unsafe counter LOSES updates, synchronized and AtomicInteger = 200 000");
                kernel.runRaceDemo();
                kernel.scenarioEnd("console menu:  key 4 (summary after the race demo)");
            }
            case "5" -> {
                input("5", "F5 - heat wave +14 C", InputDescriber.heatWaveMeaning(14.0), InputDescriber.heatWaveExpected());
                kernel.heatWave(14.0);
            }
            case "s" -> kernel.scenarioEnd("console menu:  key s");
            case "6" -> {
                Log.input("console menu", "key 6", "repair ALL sensors", InputDescriber.repairExpected("all sensors"));
                kernel.repairAll();
            }
            case "7" -> kernel.printStatusNow();
            case "8" -> kernel.printTimingReport("on request");
            case "r" -> {
                Log.input("console menu", "key r", "reset everything to normal (25 C, 60 %, 45 %, no faults)",
                        "all faults cleared, actuators OFF, voter back to OK within 1 s");
                kernel.reset();
            }
            case "h", "?" -> {
                Log.howToRead();
                printMenu();
            }
            case "9", "m" -> printMenu();
            case "0" -> {
                kernel.requestShutdown("console menu");
                return false;
            }
            default -> Log.warn("MENU", "Invalid choice '" + choice + "' - type 9 to show the menu");
        }
        return true;
    }

    /** A menu scenario: start recording events (for the summary) and print the input box. */
    private void input(String key, String scenario, String meaning, String expected) {
        EventRecorder.start(scenario, expected);
        Log.input("console menu", "key " + key + "  (" + scenario + ")", meaning, expected);
    }

    private void printMenu() {
        Log.console("""
                ======================= FAULT MENU =======================
                 1  F1  T2 wrong value (+15 C)      -> expect MASKED
                 2  F2  T3 dead                     -> expect DEGRADED
                 3  F3  T3 dead + T1 wrong value    -> expect SAFE STATE
                 4  F4  Race-condition demo
                 5  F5  Heat wave (+14 C)           -> alarm + fan ON
                 6  Repair all sensors     r  Reset system
                 7  Status now             8  Timing report
                 s  Summary of what happened since the last scenario started
                 h  How to read this screen
                 9  Show this menu         0  Exit (clean shutdown)
                ==========================================================""");
    }
}
