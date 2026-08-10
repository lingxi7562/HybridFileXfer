package top.weixiansen574.async;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;


public abstract class BackstageTask<T extends BackstageTask.BaseEventHandler> implements Runnable{
    private final T uiHandler;
    private volatile boolean executed = false;
    private volatile boolean isComplete = false;
    public BackstageTask(T uiHandler) {
        this.uiHandler = uiHandler;
    }

    protected abstract void onStart(T eventHandlerProxy) throws Throwable;

    protected void onError(Throwable th){}

    protected void onComplete(){}

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> clazz = uiHandler.getClass();
        while (clazz != null && clazz != Object.class) {
            interfaces.addAll(Arrays.asList(clazz.getInterfaces()));
            clazz = clazz.getSuperclass();
        }
        T proxyInstance = (T) Proxy.newProxyInstance(uiHandler.getClass().getClassLoader(),
                interfaces.toArray(new Class<?>[0]),
                new EvProxyHandler(uiHandler));
        try {
            onStart(proxyInstance);
            onComplete();
            TaskManger.postOnUiThread(uiHandler::onComplete);
            isComplete = true;
        } catch (Throwable e) {
            e.printStackTrace();
            onError(e);
            TaskManger.postOnUiThread(() -> uiHandler.onError(e));
        }
    }


    public synchronized void execute() {
        if (executed){
            throw new RuntimeException("Task has been executed");
        }
        executed = true;
        TaskManger.start(this);
    }

    public boolean isExecuted() {
        return executed;
    }

    public boolean isComplete() {
        return isComplete;
    }

    public static class EvProxyHandler implements InvocationHandler {
        Object evHandler;

        public EvProxyHandler(Object evHandler) {
            this.evHandler = evHandler;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class){
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "UI callback proxy for " + evHandler;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            }
            TaskManger.postOnUiThread(() -> {
                try {
                    method.invoke(evHandler,args);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
            return null;
        }
    }

    public interface BaseEventHandler{
        default void onError(Throwable th) {
            throw new RuntimeException(th);
        }

        default void onComplete() {
        }
    }

}

