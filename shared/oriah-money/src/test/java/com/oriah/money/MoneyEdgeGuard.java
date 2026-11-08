package com.oriah.money;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;

/**
 * Finds money fields at a service's API edge that are missing the ngwee rule.
 *
 * <p>Shipped in this module's test-jar. Each service with money in its
 * requests runs it over its own {@code dto} package, so a request record
 * added in a later phase (a delivery price, a cost, a fee, an expense, a
 * refund) cannot quietly accept 0.001 -- its test goes red until the field
 * carries {@link NgweeAmount}.
 *
 * <p>"Request" records are the inbound ones: every record whose name ends in
 * {@code Request}, and every record nested inside one (a line of an order).
 * Responses are outbound and never parsed from a caller.
 */
public final class MoneyEdgeGuard {

    private MoneyEdgeGuard() {
    }

    /**
     * @return {@code Class.component} for every {@link BigDecimal} component of
     *         a request record under {@code basePackage} without {@link NgweeAmount};
     *         empty when every one is guarded
     */
    public static List<String> unguardedMoneyFields(String basePackage) {
        List<String> unguarded = new ArrayList<>();
        for (Class<?> record : requestRecords(basePackage)) {
            for (RecordComponent component : record.getRecordComponents()) {
                if (component.getType() == BigDecimal.class && !guarded(record, component)) {
                    unguarded.add(record.getName() + "." + component.getName());
                }
            }
        }
        return unguarded;
    }

    /** The number of request records scanned, so a test can prove it looked. */
    public static int requestRecordCount(String basePackage) {
        return requestRecords(basePackage).size();
    }

    private static List<Class<?>> requestRecords(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        TypeFilter anyClass = (reader, factory) -> true;
        scanner.addIncludeFilter(anyClass);
        List<Class<?>> records = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = load(candidate.getBeanClassName());
            if (type.isRecord() && isRequest(type)) {
                records.add(type);
            }
        }
        return records;
    }

    private static boolean isRequest(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (c.getSimpleName().endsWith("Request")) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link NgweeAmount} has no RECORD_COMPONENT target, so the compiler
     * propagates it from the component to the private field -- which is
     * where Bean Validation reads it for a record.
     */
    private static boolean guarded(Class<?> record, RecordComponent component) {
        try {
            Field field = record.getDeclaredField(component.getName());
            return field.isAnnotationPresent(NgweeAmount.class);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("record component without a field: " + component, e);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, MoneyEdgeGuard.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
