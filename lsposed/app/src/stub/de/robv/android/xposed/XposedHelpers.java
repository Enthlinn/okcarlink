package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/** Compile-time stub of the Xposed API (provided by LSPosed at runtime). */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static void findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook hook) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Member findMethodExact(
            Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }
}
