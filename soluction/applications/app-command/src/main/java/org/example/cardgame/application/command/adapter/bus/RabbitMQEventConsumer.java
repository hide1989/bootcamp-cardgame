package org.example.cardgame.application.command.adapter.bus;


import org.example.cardgame.application.command.ConfigProperties;
import org.example.cardgame.application.command.handle.BusinessLookUp;
import org.example.cardgame.generic.DomainEvent;
import org.example.cardgame.generic.serialize.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExceptionHandlers;
import reactor.rabbitmq.Receiver;

import java.time.Duration;



@Component
public class RabbitMQEventConsumer implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQEventConsumer.class);
    private final ConfigProperties configProperties;
    private final Receiver receiver;
    private final EventSerializer eventSerializer;
    private final BusinessLookUp businessLookUp;

    public RabbitMQEventConsumer(ConfigProperties configProperties, Receiver receiver, EventSerializer eventSerializer, BusinessLookUp businessLookUp){
        this.configProperties = configProperties;
        this.receiver = receiver;
        this.eventSerializer = eventSerializer;
        this.businessLookUp = businessLookUp;
    }


    @Override
    public void run(String... args) {
        receiver.consumeManualAck(configProperties.getQueue(), new ConsumeOptions()
                .exceptionHandler(new ExceptionHandlers.RetryAcknowledgmentExceptionHandler(
                        Duration.ofSeconds(20), Duration.ofMillis(500),
                        ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE
                )))
                .flatMap(message -> {
                    var notification = Notification.from(new String(message.getBody()));
                    try {
                        DomainEvent event = eventSerializer.deserialize(
                                notification.getBody(), Class.forName(notification.getType())
                        );
                        return businessLookUp.get(event.type)
                                .flatMap(service -> service.doProcessing(event))
                                .map(e -> message);
                    } catch (ClassNotFoundException e) {
                        return Flux.error(new IllegalArgumentException(e));
                    }
                }).subscribe(message -> {
                    LOGGER.info(new String(message.getBody()));
                    message.ack();
                });
    }
}
