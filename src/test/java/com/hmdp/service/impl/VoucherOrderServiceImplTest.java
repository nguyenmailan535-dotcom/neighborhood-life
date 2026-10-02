package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.rebbitmq.MQSender;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {
    @Mock
    private RedisIdWorker idWorker;
    @Mock
    private MQSender sender;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SynchronousVoucherOrderService synchronousVoucherOrderService;

    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "redisIdWorker", idWorker);
        ReflectionTestUtils.setField(service, "mqSender", sender);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "synchronousVoucherOrderService", synchronousVoucherOrderService);
        ReflectionTestUtils.setField(service, "seckillMode", "redis-mq");
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void publishesOnlyAfterLuaAtomicallyAcceptsReservation() {
        when(idWorker.nextId("order")).thenReturn(9001L);
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());

        Result result = service.seckillVoucher(7L);

        assertTrue(result.getSuccess());
        assertEquals(9001L, result.getData());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sender).sendSeckillMessage(message.capture());
        assertTrue(message.getValue().contains("\"userId\":42"));
        assertTrue(message.getValue().contains("\"voucherId\":7"));
    }

    @Test
    void databaseModeRoutesToSynchronousTransactionalBaseline() {
        ReflectionTestUtils.setField(service, "seckillMode", "database");
        when(idWorker.nextId("order")).thenReturn(9002L);
        when(synchronousVoucherOrderService.createOrder(9002L, 42L, 7L))
                .thenReturn(Result.ok(9002L));

        Result result = service.seckillVoucher(7L);

        assertTrue(result.getSuccess());
        assertEquals(9002L, result.getData());
        verify(synchronousVoucherOrderService).createOrder(9002L, 42L, 7L);
        verifyNoInteractions(redisTemplate, sender);
    }
}
