package com.hmdp.rebbitmq;

import com.alibaba.fastjson.JSON;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MQReceiverTest {
    @Mock
    private IVoucherOrderService orderService;
    @Mock
    private Channel channel;

    @Test
    void acknowledgesOnlyAfterTransactionalPersistence() throws Exception {
        VoucherOrder order = order();
        MQReceiver receiver = new MQReceiver(orderService);

        receiver.receiveSeckillMessage(message(order, 7L), channel);

        verify(orderService).persistVoucherOrder(order);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void propagatesFailureSoContainerRetryAndDlqCanRun() throws Exception {
        VoucherOrder order = order();
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderService).persistVoucherOrder(order);
        MQReceiver receiver = new MQReceiver(orderService);

        assertThrows(IllegalStateException.class,
                () -> receiver.receiveSeckillMessage(message(order, 8L), channel));
    }

    private VoucherOrder order() {
        VoucherOrder order = new VoucherOrder();
        order.setId(100L);
        order.setUserId(10L);
        order.setVoucherId(20L);
        return order;
    }

    private Message message(VoucherOrder order, long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message(JSON.toJSONString(order).getBytes(StandardCharsets.UTF_8), properties);
    }
}
