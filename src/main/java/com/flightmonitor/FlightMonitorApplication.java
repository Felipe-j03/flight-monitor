package com.flightmonitor;

import com.flightmonitor.config.FlightMonitorProperties;
import com.flightmonitor.config.TelegramConfig;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FlightMonitorProperties.class, TelegramConfig.class})
public class FlightMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightMonitorApplication.class, args);
    }

    /**
     * A single injected clock. Every timestamp the application writes goes through it, which is
     * what lets the date and cooldown rules be tested at a fixed instant instead of "now".
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
