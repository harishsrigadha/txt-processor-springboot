package com.example.txn.config;

import com.example.txn.service.FailureInjector;
import com.example.txn.service.ScriptedFailureInjector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Downstream failure simulation. In production this would be a real downstream client call. */
    @Bean
    public FailureInjector failureInjector(@Value("${txn.demo-failures:false}") boolean demoFailures) {
        if (!demoFailures) {
            return FailureInjector.NONE;
        }
        return new ScriptedFailureInjector().failTimes("T2", 2).failAlways("T7");
    }
}
