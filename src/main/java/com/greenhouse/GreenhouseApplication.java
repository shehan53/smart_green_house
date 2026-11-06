package com.greenhouse;

import com.greenhouse.monitor.ConsoleCopy;
import com.greenhouse.monitor.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #6 Threads / clean shutdown (entry point of the whole system)
 * Purpose: Main class. Starts Spring Boot (REST API for Postman). The RealTimeKernel then starts
 *          all periodic real-time tasks on a ScheduledExecutorService and stops them cleanly.
 */
@SpringBootApplication
public class GreenhouseApplication {

    public static void main(String[] args) {
        ConsoleCopy.install();   // from now on everything in the CLI is also written to the full log file
        Log.sessionStart();      // first lines: session start time + where the two log files are
        SpringApplication.run(GreenhouseApplication.class, args);
    }
}
