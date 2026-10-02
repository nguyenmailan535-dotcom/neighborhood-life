package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlidingWindowRateLimitAspectTest {
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private SlidingWindowRateLimitAspect aspect;

    @BeforeEach
    void setUp() throws Exception {
        aspect = new SlidingWindowRateLimitAspect(redisTemplate);
        Method method = Fixture.class.getMethod("limited");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejectsBeforeControllerWhenAnyRedisWindowIsFull() throws Throwable {
        doReturn(-1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());

        Result result = (Result) aspect.enforce(joinPoint);

        assertFalse(result.getSuccess());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void proceedsAfterAllDimensionsPass() throws Throwable {
        Result expected = Result.ok("accepted");
        doReturn(999L, 29L, 2L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
        when(joinPoint.proceed()).thenReturn(expected);

        assertSame(expected, aspect.enforce(joinPoint));
        verify(joinPoint).proceed();
    }

    static class Fixture {
        @SlidingWindowRateLimit(dimension = RateLimitDimension.API, limit = 1000, windowSeconds = 1)
        @SlidingWindowRateLimit(dimension = RateLimitDimension.IP, limit = 30, windowSeconds = 1)
        @SlidingWindowRateLimit(dimension = RateLimitDimension.USER, limit = 3, windowSeconds = 1)
        public void limited() {
        }
    }
}
