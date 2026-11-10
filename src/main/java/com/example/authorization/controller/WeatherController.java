package com.example.authorization.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WeatherController {

    @GetMapping("/weather")
    public Map<String, String> getWeather() {
        return Map.of("forecast", "Sunny, 25C for the next 12 hours");
    }
}
