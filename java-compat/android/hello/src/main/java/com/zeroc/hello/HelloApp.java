//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

package com.zeroc.hello;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.LinkedList;
import java.util.List;

import Ice.Communicator;

public class HelloApp extends Application
{
    static class MessageReady
    {
        MessageReady(Communicator c, Ice.LocalException e)
        {
            communicator = c;
            ex = e;
        }

        Communicator communicator;
        Ice.LocalException ex;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        _uiHandler = new Handler(Looper.getMainLooper())
        {
            @Override
            public void handleMessage(Message m)
            {
                if(m.what == MSG_READY)
                {
                    MessageReady ready = (MessageReady)m.obj;
                    _initialized = true;
                    _communicator = ready.communicator;
                }
                else if(m.what == MSG_EXCEPTION || m.what == MSG_RESPONSE)
                {
                    _result = null;
                }

                Message copy = new Message();
                copy.copyFrom(m);

                if(_handler != null)
                {
                    _handler.sendMessage(copy);
                }
                else
                {
                    _queue.add(copy);
                }
            }
        };

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        //
        // On some devices, a multicast lock must be acquired otherwise multicast packets are discarded.
        // The lock is initially unacquired.
        //
        WifiManager.MulticastLock lock = wifiManager.createMulticastLock("com.zeroc.hello");
        lock.acquire();

        // SSL initialization can take some time. To avoid blocking the
        // calling thread, we perform the initialization in a separate thread.
        new Thread(() -> {
            try
            {
                Ice.InitializationData initData = new Ice.InitializationData();

                initData.dispatcher = (runnable, connection) -> _uiHandler.post(runnable);

                initData.properties = Ice.Util.createProperties();
                initData.properties.setProperty("Ice.Trace.Network", "3");

                initData.properties.setProperty("IceSSL.Trace.Security", "3");
                initData.properties.setProperty("IceSSL.KeystoreType", "BKS");
                initData.properties.setProperty("IceSSL.TruststoreType", "BKS");
                initData.properties.setProperty("IceSSL.Password", "password");
                initData.properties.setProperty("Ice.Plugin.IceSSL", "IceSSL.PluginFactory");
                initData.properties.setProperty("Ice.Plugin.IceDiscovery", "IceDiscovery.PluginFactory");

                //
                // We need to postpone plug-in initialization so that we can configure IceSSL
                // with a resource stream for the certificate information.
                //
                initData.properties.setProperty("Ice.InitPlugins", "0");

                Communicator c = Ice.Util.initialize(initData);

                //
                // Now we complete the plug-in initialization.
                //
                IceSSL.Plugin plugin = (IceSSL.Plugin)c.getPluginManager().getPlugin("IceSSL");
                //
                // Be sure to pass the same input stream to the SSL plug-in for
                // both the keystore and the truststore. This makes startup a
                // little faster since the plug-in will not initialize
                // two keystores.
                //
                java.io.InputStream certs = getResources().openRawResource(R.raw.client);
                plugin.setKeystoreStream(certs);
                plugin.setTruststoreStream(certs);
                c.getPluginManager().initializePlugins();

                _uiHandler.sendMessage(Message.obtain(_uiHandler, MSG_READY, new MessageReady(c, null)));
            }
            catch(Ice.LocalException e)
            {
                _uiHandler.sendMessage(Message.obtain(_uiHandler, MSG_READY, new MessageReady(null, e)));
            }
        }).start();
    }

    /** Called when the application is stopping. */
    @Override
    public void onTerminate()
    {
        super.onTerminate();
        if(_communicator != null)
        {
            _communicator.destroy();
        }
    }

    void setHandler(Handler handler)
    {
        // Nothing to do in this case.
        if(_handler != handler)
        {
            _handler = handler;

            if(_handler != null)
            {
                if(!_initialized)
                {
                    _handler.sendMessage(_handler.obtainMessage(MSG_WAIT));
                }
                else
                {
                    // Send all the queued messages.
                    while(!_queue.isEmpty())
                    {
                        _handler.sendMessage(_queue.remove(0));
                    }
                }
            }
        }
    }

    void setHost(String host)
    {
        _host = host;
        _proxy = null;
    }

    void setUseDiscovery(boolean b)
    {
        _useDiscovery = b;
        _proxy = null;
    }

    void setTimeout(int timeout)
    {
        _timeout = timeout;
        _proxy = null;
    }

    void setDeliveryMode(DeliveryMode mode)
    {
        _mode = mode;
        _proxy = null;
    }

    void flush()
    {
        if(_proxy != null)
        {
            _proxy.begin_ice_flushBatchRequests(new Ice.Callback_Object_ice_flushBatchRequests() {
                @Override
                public void exception(final Ice.LocalException ex)
                {
                    _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
                }
            });
        }
    }

    void shutdown()
    {
        try
        {
            updateProxy();
            if(_proxy == null)
            {
                return;
            }
            _proxy.shutdown();
        }
        catch(Ice.LocalException ex)
        {
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
        }

    }

    void shutdownAsync()
    {
        try
        {
            updateProxy();
            if(_proxy == null || _result != null)
            {
                return;
            }

            _resultMode = _mode;
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_SENDING));
            _result = _proxy.begin_shutdown(new Demo.Callback_Hello_shutdown()
            {
                @Override
                synchronized public void exception(final Ice.LocalException ex)
                {
                    _response = true;
                    _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
                }

                @Override
                synchronized public void response()
                {
                    _response = true;
                    _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_RESPONSE));
                }

                @Override
                synchronized public void sent(boolean sentSynchronously)
                {
                    if(_resultMode.isOneway())
                    {
                        _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_RESPONSE));
                    }
                    else if(!_response)
                    {
                        _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_SENT, _resultMode));
                    }
                }
                // There is no ordering guarantee between sent, response/exception.
                private boolean _response = false;
            });
        }
        catch(Ice.LocalException ex)
        {
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
        }
    }

    void sayHello(int delay)
    {
        try
        {
            updateProxy();
            if(_proxy == null || _result != null)
            {
                return;
            }

            _proxy.begin_sayHello(delay);
        }
        catch(Ice.LocalException ex)
        {
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
        }
    }

    void sayHelloAsync(int delay)
    {
        try
        {
            updateProxy();
            if(_proxy == null || _result != null)
            {
                return;
            }

            _resultMode = _mode;
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_SENDING));
            _result = _proxy.begin_sayHello(delay,
                    new Demo.Callback_Hello_sayHello()
                    {
                        @Override
                        synchronized public void exception(final Ice.LocalException ex)
                        {
                            _response = true;
                            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
                        }

                        @Override
                        synchronized public void response()
                        {
                            _response = true;
                            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_RESPONSE));
                        }

                        @Override
                        synchronized public void sent(boolean sentSynchronously)
                        {
                            if(_resultMode.isOneway())
                            {
                                _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_RESPONSE));
                            }
                            else if(!_response)
                            {
                                _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_SENT, _resultMode));
                            }
                        }
                        // There is no ordering guarantee between sent, response/exception.
                        private boolean _response = false;
                    });
        }
        catch(Ice.LocalException ex)
        {
            _uiHandler.sendMessage(_uiHandler.obtainMessage(MSG_EXCEPTION, ex));
        }
    }

    private void updateProxy()
    {
        if(_proxy != null)
        {
            return;
        }

        String s;
        if(_useDiscovery)
        {
            s = "hello";
        }
        else
        {
            s = "hello:tcp -h " + _host + " -p 10000:ssl -h " + _host + " -p 10001:udp -h " + _host  + " -p 10000";
        }
        Ice.ObjectPrx prx = _communicator.stringToProxy(s);
        prx = _mode.apply(prx);
        if(_timeout != 0)
        {
            prx = prx.ice_invocationTimeout(_timeout);
        }

        _proxy = Demo.HelloPrxHelper.uncheckedCast(prx);
    }

    DeliveryMode getDeliveryMode()
    {
        return _mode;
    }

    public static final int MSG_WAIT = 0;
    public static final int MSG_READY = 1;
    public static final int MSG_EXCEPTION = 2;
    public static final int MSG_RESPONSE = 3;
    public static final int MSG_SENDING = 4;
    public static final int MSG_SENT = 5;

    private final List<Message> _queue = new LinkedList<>();
    private Handler _uiHandler;

    private boolean _initialized;
    private Ice.Communicator _communicator;
    private Demo.HelloPrx _proxy = null;

    // The current request if any.
    private Ice.AsyncResult _result;
    // The mode of the current request.
    private DeliveryMode _resultMode;
    private Handler _handler;

    // Proxy settings.
    private String _host;
    private boolean _useDiscovery;
    private int _timeout;
    private DeliveryMode _mode;
}
