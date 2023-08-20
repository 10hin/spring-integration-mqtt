package com.example.springintegrationmqtt.web;

import com.example.springintegrationmqtt.SpringIntegrationMqttApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/mqtt/")
public class MQTTController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MQTTController.class);

    private final SpringIntegrationMqttApplication.Mqttv5PublishGateway mqtt5Gateway;

    public MQTTController(final SpringIntegrationMqttApplication.Mqttv5PublishGateway mqtt5Gateway) {
        this.mqtt5Gateway = mqtt5Gateway;
    }

    @PostMapping("topics/{topic}")
    public Mono<ResponseEntity<Void>> publish(
            @PathVariable("topic") final String topic,
            @RequestBody final Mono<String> body
    ) {

        return body.doOnNext((msg) -> this.mqtt5Gateway.publish(topic, msg))
                .map(msg -> ResponseEntity.ok().<Void>build())
                .onErrorResume(ex -> {
                    LOGGER.error("failed to publish message", ex);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });

    }
}
