package com.hmdp.rebbitmq;

import com.hmdp.config.RabbitMQTopicConfig;
import com.alibaba.fastjson.JSON;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息发送者
 */
@Slf4j
@Service
public class MQSender {
    private final RabbitTemplate rabbitTemplate;
    private final SeckillPendingMessageStore pendingStore;
    private final Set<String> returnedOrderIds = ConcurrentHashMap.newKeySet();

    public MQSender(RabbitTemplate rabbitTemplate, SeckillPendingMessageStore pendingStore) {
        this.rabbitTemplate = rabbitTemplate;
        this.pendingStore = pendingStore;
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory) {
            CachingConnectionFactory connectionFactory =
                    (CachingConnectionFactory) rabbitTemplate.getConnectionFactory();
            connectionFactory.setPublisherConfirmType(
                    CachingConnectionFactory.ConfirmType.CORRELATED);
            connectionFactory.setPublisherReturns(true);
        }
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (correlation == null) {
                return;
            }
            if (ack && !returnedOrderIds.remove(correlation.getId())) {
                pendingStore.markConfirmed(correlation.getId());
            } else {
                log.error("RabbitMQ rejected seckill order {}, cause={}", correlation.getId(), cause);
            }
        });
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            Object orderId = message.getMessageProperties().getHeaders().get("x-order-id");
            if (orderId != null) {
                returnedOrderIds.add(orderId.toString());
            }
                log.error("Unroutable seckill message, code={}, text={}, exchange={}, routingKey={}",
                        replyCode, replyText, exchange, routingKey);
        });
    }

    public void sendSeckillMessage(String message) {
        VoucherOrder order = JSON.parseObject(message, VoucherOrder.class);
        publish(order.getId().toString(), message);
    }

    @Scheduled(fixedDelayString = "${app.seckill.outbox-retry-ms:5000}")
    public void retryUnconfirmedMessages() {
        for (Map.Entry<Object, Object> entry : pendingStore.list(100).entrySet()) {
            publish(entry.getKey().toString(), entry.getValue().toString());
        }
    }

    private void publish(String orderId, String message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQTopicConfig.EXCHANGE,
                    RabbitMQTopicConfig.ROUTING_KEY,
                    message,
                    rabbitMessage -> {
                        rabbitMessage.getMessageProperties().setHeader("x-order-id", orderId);
                        return rabbitMessage;
                    },
                    new CorrelationData(orderId));
        } catch (RuntimeException e) {
            log.error("Failed to publish seckill order {}; retained in Redis outbox", orderId, e);
        }
    }
}
