package com.example.springintegrationmqtt;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.gateway.MethodArgsMessageMapper;
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.Mqttv5PahoMessageHandler;
import org.springframework.integration.mqtt.support.MqttHeaderMapper;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.SmartMessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Hooks;

import java.util.Objects;
import java.util.function.Consumer;

@SpringBootApplication
public class SpringIntegrationMqttApplication {
	private static final Logger LOGGER = LoggerFactory.getLogger(SpringIntegrationMqttApplication.class);
	private static final String MQTTv5_TRACEPARENT_USER_PROPERTY_KEY = "traceparent";

	public static void main(String[] args) {
		Hooks.enableAutomaticContextPropagation();
		SpringApplication.run(SpringIntegrationMqttApplication.class, args);
	}

	@Bean
	public <T extends ObservationRegistry> ObservationRegistryCustomizer<T> observationRegistryCustomizer() {
		return ObservationThreadLocalAccessor.getInstance()::setObservationRegistry;
	}

	/* mqtt inbound */
	@Bean
	public MessageChannel mqttInboundChannel() {
		return new DirectChannel();
	}

	@Bean
	public SmartMessageConverter messageConverter() {
		return new ByteArrayMessageConverter();
	}

	@Bean
	public MessageProducer inbound(
			@Qualifier("mqttInboundChannel")
			final MessageChannel mqttInboundChannel,
			final SmartMessageConverter messageConverter
	) {
		final var messageProducer =  new Mqttv5PahoMessageDrivenChannelAdapter("tcp://localhost:1883", "mqtt5clientsub", "topic1");
		messageProducer.setPayloadType(String.class);
		messageProducer.setMessageConverter(messageConverter);
		messageProducer.setManualAcks(true);
		messageProducer.setOutputChannel(mqttInboundChannel);
		messageProducer.setQos(2);

		return messageProducer;
	}

	@Bean
	public MessageHandler inboundUserHandler() {
		return message -> LOGGER.info("inboundHandler receive message: {}", message);
	}

	/**
	 *
	 * @param tracer
	 * @param inboundUserHandler
	 * @return
	 */
	@Bean
	@ServiceActivator(inputChannel = "mqttInboundChannel")
	public MessageHandler inboundHandler(
			final Tracer tracer,
			@Qualifier("inboundUserHandler")
			final MessageHandler inboundUserHandler
	) {
		return message -> {
			String traceParent = null;
			try {
				traceParent = (String) message.getHeaders().get(MQTTv5_TRACEPARENT_USER_PROPERTY_KEY);
			} catch (final ClassCastException cce) {
				LOGGER.warn("failed to cast traceParent to String: {}", message.getHeaders().get(MQTTv5_TRACEPARENT_USER_PROPERTY_KEY));
			}
			if (traceParent != null) {
				final var traceContext = extractTraceParent(tracer, traceParent);
				if (traceContext != null) {
					try (final var ignoredParentScope = tracer.currentTraceContext().newScope(traceContext)) {
						final var newSpan = tracer.nextSpan().name("receive-mqtt");
						try (final var ignoredNewScope = tracer.withSpan(newSpan.start())) {
							inboundUserHandler.handleMessage(message);
						} finally {
							newSpan.end();
						}
						return;
					}
				}
			}
			inboundUserHandler.handleMessage(message);
		};
	}
	/* /mqtt inbound */

	/* mqtt outbound */
	@Bean
	public MqttHeaderMapper mqttOutboundHeaderMapper() {
		return new MqttHeaderMapper();
	}
	@Bean
	public MessageChannel mqttOutboundChannel() {
		return new DirectChannel();
	}
	@Bean
	@ServiceActivator(inputChannel = "mqttOutboundChannel")
	public Mqttv5PahoMessageHandler messageHandler(
			final MqttHeaderMapper mqttOutboundHeaderMapper,
			final SmartMessageConverter messageConverter
	) {
		final var handler = new Mqttv5PahoMessageHandler("tcp://localhost:1883", "mqtt5clientpub");
		handler.setHeaderMapper(mqttOutboundHeaderMapper);
		handler.setDefaultTopic("topic1");
		handler.setAsync(true);
		handler.setAsyncEvents(true);
		handler.setConverter(messageConverter);
		return handler;
	}
	@MessagingGateway(
			defaultRequestChannel = "mqttOutboundChannel",
			mapper = "mqttOutboundMessageMapper"
	)
	public interface Mqttv5PublishGateway {
		void publish(final String topic, final String data);
	}
	@Bean
	public MethodArgsMessageMapper mqttOutboundMessageMapper(final Tracer tracer) {
		return (argsHolder, headers) -> {
			final var sendSpan = tracer.nextSpan().name("send-mqtt");
			try (final var ignoredSendScope = tracer.withSpan(sendSpan.start())) {
				final var traceContext = sendSpan.context();
				return MessageBuilder.withPayload(argsHolder.getArgs()[1])
						.copyHeaders(headers)
						.setHeader("mqtt_topic", argsHolder.getArgs()[0])
						.setHeader(MQTTv5_TRACEPARENT_USER_PROPERTY_KEY, createTraceParent(traceContext))
						.build();
			} finally {
				sendSpan.end();
			}
		};
	}
	/* /mqtt outbound */

	private static String createTraceParent(final TraceContext traceContext) {
		return "00-"
				+traceContext.traceId()
				+"-"
				+traceContext.spanId()
				+"-"
				+(traceContext.sampled() ? "01":"00");
	}

	@Nullable
	private static TraceContext extractTraceParent(@NonNull final Tracer tracer, @NonNull final String traceParent) {
		Objects.requireNonNull(traceParent, "traceParent is null");

		final var fields = traceParent.split("-");
		if (fields.length < 3) {
			LOGGER.warn("traceParent exists, but it has illegal format: {}", traceParent);
			return null;
		}

		if (!fields[0].equals("00")) {
			LOGGER.warn("traceParent exists, but it has unknown version: {}", traceParent);
			return null;
		}

		final var traceID = fields[1];
		final var spanID = fields[2];
		final boolean sampled;
		if (fields.length > 3) {
			if (fields[3].equals("01")) {
				sampled = true;
			} else {
				sampled = !fields[3].equals("00");
			}
		} else {
			sampled = true;
		}

		return tracer.traceContextBuilder()
				.traceId(traceID)
				.spanId(spanID)
				.sampled(sampled)
				.build();

	}

}
