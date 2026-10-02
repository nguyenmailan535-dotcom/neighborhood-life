package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMQTopicConfigTest {
    private final RabbitMQTopicConfig config = new RabbitMQTopicConfig();

    @Test
    void mainQueueIsDurableAndRoutesExhaustedFailuresToDlq() {
        Queue queue = config.seckillQueue();

        assertTrue(queue.isDurable());
        assertEquals(RabbitMQTopicConfig.DEAD_LETTER_EXCHANGE,
                queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitMQTopicConfig.DEAD_LETTER_ROUTING_KEY,
                queue.getArguments().get("x-dead-letter-routing-key"));
    }
}
