package com.hmdp.rebbitmq;

import com.alibaba.fastjson.JSON;
import com.hmdp.config.RabbitMQTopicConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 消息消费者
 */
@Slf4j
@Service
public class MQReceiver {
    private final IVoucherOrderService voucherOrderService;

    public MQReceiver(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @RabbitListener(
            queues = RabbitMQTopicConfig.QUEUE,
            containerFactory = "reliableRabbitListenerContainerFactory",
            autoStartup = "${app.rabbit.listener-enabled:true}")
    public void receiveSeckillMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        VoucherOrder order = JSON.parseObject(payload, VoucherOrder.class);
        try {
            voucherOrderService.persistVoucherOrder(order);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicate) {
            log.info("Idempotent duplicate seckill order ignored, orderId={}", order.getId());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException failure) {
            log.error("Seckill order persistence failed, orderId={}; listener retry will handle it",
                    order.getId(), failure);
            throw failure;
        }
    }
}
