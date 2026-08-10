package top.weixiansen574.hybridfilexfer.tasks;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.droidcore.DroidHFXClient;

public class ClientTask extends BackstageTask<ClientTask.Callback> {
    private final DroidHFXClient client;

    public ClientTask(Callback uiHandler, DroidHFXClient client) {
        super(uiHandler);
        this.client = client;
    }

    @Override
    protected void onStart(Callback eventHandlerProxy) throws Throwable {
        client.start(eventHandlerProxy);
    }

    @Override
    protected void onComplete() {
        client.close();
        client.freeBuffers();
    }

    @Override
    protected void onError(Throwable th) {
        client.close();
        client.freeBuffers();
    }

    public interface Callback extends ClientCallBack,BackstageTask.BaseEventHandler{}
}
