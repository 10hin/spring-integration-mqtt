package com.example.springintegrationmqtt.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/")
public class MainController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainController.class);
    @GetMapping
    public Mono<ResponseEntity<Void>> index() {
        LOGGER.info("MainController.index() called");
        return Mono.just(ResponseEntity.ok().build());
    }
}
