package com.travel.travel_system.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class LogAspect {

    private static final Logger logger = LoggerFactory.getLogger(LogAspect.class);

    // 定义切入点，拦截所有controller、service和utils包中的方法
//    @Pointcut("within(com.travel.travel_system.controller..*) ||" +
//            " within(com.travel.travel_system.service..*) ||" +
//            " within(com.travel.travel_system.utils..*)")
//    public void logPointcut() {}
//
//    // 前置通知，方法执行前记录日志
//    @Before("logPointcut()")
//    public void logBefore(JoinPoint joinPoint) {
//        String methodName = joinPoint.getSignature().getName();
//        String className = joinPoint.getTarget().getClass().getName();
//        Object[] args = joinPoint.getArgs();
//        logger.info("[{}] 开始执行方法: {}，参数: {}", className, methodName, Arrays.toString(args));
//    }
//
//    // 环绕通知，记录方法执行时间
//    @Around("logPointcut()")
//    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
//        long startTime = System.currentTimeMillis();
//        Object result = joinPoint.proceed();
//        long endTime = System.currentTimeMillis();
//        String methodName = joinPoint.getSignature().getName();
//        String className = joinPoint.getTarget().getClass().getName();
//        logger.info("[{}] 方法: {} 执行完成，耗时: {}ms", className, methodName, (endTime - startTime));
//        return result;
//    }
//
//    // 后置通知，方法执行后记录日志
//    @After("logPointcut()")
//    public void logAfter(JoinPoint joinPoint) {
//        String methodName = joinPoint.getSignature().getName();
//        String className = joinPoint.getTarget().getClass().getName();
//        logger.debug("[{}] 方法: {} 执行结束", className, methodName);
//    }
//
//    // 异常通知，记录方法执行异常
//    @AfterThrowing(pointcut = "logPointcut()", throwing = "e")
//    public void logAfterThrowing(JoinPoint joinPoint, Throwable e) {
//        String methodName = joinPoint.getSignature().getName();
//        String className = joinPoint.getTarget().getClass().getName();
//        logger.error("[{}] 方法: {} 执行异常: {}", className, methodName, e.getMessage(), e);
//    }
}
