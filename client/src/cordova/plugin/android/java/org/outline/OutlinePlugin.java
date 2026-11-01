// Copyright 2018 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.outline.log.OutlineLogger;
import org.outline.log.SentryErrorReporter;
import org.outline.vpn.Errors;
import org.outline.vpn.VpnServiceStarter;
import org.outline.vpn.VpnTunnelService;

import outline.GoBackendConfig;
import outline.Outline;
import outline.InvokeMethodResult;
import platerrors.Platerrors;
import platerrors.PlatformError;

import static org.outline.vpn.VpnTunnelService.MessageData;
import static org.outline.vpn.VpnTunnelService.TunnelStatus;

public class OutlinePlugin extends CordovaPlugin {
  private static final Logger LOG = Logger.getLogger(OutlinePlugin.class.getName());

  // Actions supported by this plugin.
  public enum Action {
    INVOKE_METHOD("invokeMethod"),
    START("start"),
    STOP("stop"),
    ON_STATUS_CHANGE("onStatusChange"),
    IS_RUNNING("isRunning"),
    INIT_ERROR_REPORTING("initializeErrorReporting"),
    REPORT_EVENTS("reportEvents"),
    QUIT("quitApplication");

    private final static Map<String, Action> actions = new HashMap<>();
    static {
      for (Action action : Action.values()) {
        actions.put(action.value, action);
      }
    }

    // Returns whether |value| is a defined action.
    public static boolean hasValue(final String value) {
      return actions.containsKey(value);
    }

    public final String value;
    Action(final String value) {
      this.value = value;
    }

    // Returns whether |action| is the underlying value of this instance.
    public boolean is(final String action) {
      return this.value.equals(action);
    }
  }

  // Encapsulates parameters to start the VPN asynchronously after requesting user permission.
  private static class StartVpnRequest {
    public final JSONArray args;
    public final CallbackContext callback;
    public StartVpnRequest(JSONArray args, CallbackContext callback) {
      this.args = args;
      this.callback = callback;
    }
  }

  private static final int REQUEST_CODE_PREPARE_VPN = 100;

  // How long an action waits for the system to (re)connect the VPN service before rebinding it from
  // scratch, and then how long it waits on that fresh binding before giving up. A healthy bind
  // takes well under a second, and the system restarts a crashed service after about one.
  private static final long SERVICE_BIND_TIMEOUT_MS = 4000;

  // AIDL interface for VpnTunnelService, which is bound for the lifetime of this class.
  // The VpnTunnelService runs in a sub process and is thread-safe.
  // The service process can die at any time, and some OEM builds (notably Xiaomi HyperOS) kill it
  // in the background and never restart it. Actions therefore get the service through
  // getVpnTunnelService(), which rebinds from scratch when the binding is not coming back.
  // Guarded by serviceLock, which is also notified whenever the service connects.
  private IVpnTunnelService vpnTunnelService;
  // Set when the binding can no longer deliver a service: the system reported it dead, or a call
  // found the service proxy dead. Guarded by serviceLock.
  private boolean bindingBroken;
  private final Object serviceLock = new Object();
  private volatile String errorReportingApiKey;
  private StartVpnRequest startVpnRequest;
  // Tunnel status change callback.
  private CallbackContext statusCallback;

  // Connection to the VPN service.
  private final ServiceConnection vpnServiceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
      synchronized (serviceLock) {
        vpnTunnelService = IVpnTunnelService.Stub.asInterface(binder);
        bindingBroken = false;
        serviceLock.notifyAll();
      }
      LOG.info("VPN service connected");
    }

    // The system will never reconnect this binding: it has to be unbound and bound again. That
    // happens on the next action rather than here, so an app in the background does not fight an
    // OS that keeps killing the service.
    @Override
    public void onBindingDied(ComponentName className) {
      LOG.warning("VPN service binding died");
      synchronized (serviceLock) {
        vpnTunnelService = null;
        bindingBroken = true;
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      LOG.warning("VPN service disconnected");
      // Drop the proxy to the dead process. Calls on it would fail with DeadObjectException for as
      // long as the service stays down, which on some devices is until the app is force stopped.
      synchronized (serviceLock) {
        vpnTunnelService = null;
      }
      // Rebind the service so the VPN automatically reconnects if the service process crashed.
      Context context = getBaseContext();
      Intent rebind = new Intent(context, VpnTunnelService.class);
      rebind.putExtra(VpnServiceStarter.AUTOSTART_EXTRA, true);
      // Send the error reporting API key so the potential crash is reported.
      rebind.putExtra(MessageData.ERROR_REPORTING_API_KEY.value, errorReportingApiKey);
      context.bindService(rebind, vpnServiceConnection, Context.BIND_AUTO_CREATE);
    }
  };

  @Override
  protected void pluginInitialize() {
    OutlineLogger.registerLogHandler(SentryErrorReporter.BREADCRUMB_LOG_HANDLER);
    Context context = getBaseContext();

    final GoBackendConfig goConfig = Outline.getBackendConfig();
    goConfig.setDataDir(context.getFilesDir().getAbsolutePath());

    IntentFilter broadcastFilter = new IntentFilter();
    broadcastFilter.addAction(VpnTunnelService.STATUS_BROADCAST_KEY);
    broadcastFilter.addCategory(context.getPackageName());
    context.registerReceiver(vpnTunnelBroadcastReceiver, broadcastFilter, context.RECEIVER_NOT_EXPORTED);

    context.bindService(new Intent(context, VpnTunnelService.class), vpnServiceConnection,
        Context.BIND_AUTO_CREATE);
  }

  @Override
  public void onDestroy() {
    Context context = getBaseContext();
    context.unregisterReceiver(vpnTunnelBroadcastReceiver);
    try {
      context.unbindService(vpnServiceConnection);
    } catch (IllegalArgumentException e) {
      // Not bound: a rebind was interrupted between its unbind and bind.
    }
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
    if (!Action.hasValue(action)) {
      return false;
    }
    if (Action.QUIT.is(action)) {
      this.cordova.getActivity().finish();
      return true;
    }

    LOG.fine(String.format(Locale.ROOT, "Received action: %s", action));

    if (Action.ON_STATUS_CHANGE.is(action)) {
      this.statusCallback = callbackContext;
      // TODO(fortuna): unregister original with Cordova.
      return true;
    }

    if (Action.START.is(action)) {
      // Prepare the VPN before spawning a new thread. Fall through if it's already prepared.
      try {
        if (!prepareVpnService()) {
          startVpnRequest = new StartVpnRequest(args, callbackContext);
          return true;
        }
      } catch (ActivityNotFoundException e) {
        sendActionResult(callbackContext, new PlatformError(Platerrors.InternalError, e.toString()));
        return true;
      }
    }

    executeAsync(action, args, callbackContext);
    return true;
  }

  // Executes an action asynchronously through the Cordova thread pool.
  private void executeAsync(
      final String action, final JSONArray args, final CallbackContext callback) {
    cordova.getThreadPool().execute(() -> {
      try {
        if (Action.INVOKE_METHOD.is(action)) {
          final String methodName = args.getString(0);
          final String input = args.getString(1);
          LOG.fine(String.format(Locale.ROOT, "Calling InvokeMethod(%s, %s)", methodName, input));
          final InvokeMethodResult result = Outline.invokeMethod(methodName, input);
          if (result.getError() != null) {
            LOG.warning(String.format(Locale.ROOT, "InvokeMethod(%s) failed: %s", methodName, result.getError()));
            sendActionResult(callback, result.getError());
          } else {
            LOG.fine(String.format(Locale.ROOT, "InvokeMethod(%s) result: %s", methodName, result.getValue()));
            callback.success(result.getValue());
          }

        // Tunnel instance actions: tunnel ID is always the first argument.
        } else if (Action.START.is(action)) {
          final String tunnelId = args.getString(0);
          final String serverName = args.getString(1);
          final String transportConfig = args.getString(2);
          sendActionResult(callback, startVpnTunnel(tunnelId, transportConfig, serverName));
        } else if (Action.STOP.is(action)) {
          final String tunnelId = args.getString(0);
          LOG.info(String.format(Locale.ROOT, "Stopping VPN tunnel %s", tunnelId));
          sendActionResult(callback, getVpnTunnelService().stopTunnel(tunnelId));
        } else if (Action.IS_RUNNING.is(action)) {
          final String tunnelId = args.getString(0);
          boolean isActive = isTunnelActive(tunnelId);
          callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, isActive));

          // Static actions
        } else if (Action.INIT_ERROR_REPORTING.is(action)) {
          errorReportingApiKey = args.getString(0);
          // Treat failures to initialize error reporting as unexpected by propagating exceptions.
          SentryErrorReporter.init(getBaseContext(), errorReportingApiKey);
          getVpnTunnelService().initErrorReporting(errorReportingApiKey);
          callback.success();
        } else if (Action.REPORT_EVENTS.is(action)) {
          final String uuid = args.getString(0);
          SentryErrorReporter.send(uuid);
          callback.success();
        } else {
          throw new IllegalArgumentException(
              String.format(Locale.ROOT, "Unexpected action %s", action));
        }
      } catch (Exception e) {
        LOG.log(Level.SEVERE,
            String.format(Locale.ROOT, "Unexpected error while executing action: %s", action), e);
        sendActionResult(callback, new PlatformError(Platerrors.InternalError, e.toString()));
      }
    });
  }

  // Requests user permission to connect the VPN. Returns true if permission was previously granted,
  // and false if the OS prompt will be displayed.
  private boolean prepareVpnService() throws ActivityNotFoundException {
    LOG.fine("Preparing VPN.");
    Intent prepareVpnIntent = VpnService.prepare(getBaseContext());
    if (prepareVpnIntent == null) {
      return true;
    }
    LOG.info("Prepare VPN with activity");
    cordova.startActivityForResult(this, prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
    return false;
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    if (request != REQUEST_CODE_PREPARE_VPN) {
      LOG.warning("Received non-requested activity result.");
      return;
    }
    if (result != Activity.RESULT_OK) {
      LOG.warning("Failed to prepare VPN.");
      sendActionResult(startVpnRequest.callback, new PlatformError(
          Platerrors.VPNPermissionNotGranted, "failed to grant the VPN permission"));
      return;
    }
    executeAsync(Action.START.value, startVpnRequest.args, startVpnRequest.callback);
    startVpnRequest = null;
  }

  private DetailedJsonError startVpnTunnel(
      final String tunnelId, final String transportConfig, final String serverName
  ) throws RemoteException {
    LOG.info(String.format(Locale.ROOT, "Starting VPN tunnel %s for server %s", tunnelId, serverName));
    final TunnelConfig tunnelConfig = new TunnelConfig();
    tunnelConfig.id = tunnelId;
    tunnelConfig.name = serverName;
    tunnelConfig.transportConfig = transportConfig;
    try {
      return getVpnTunnelService().startTunnel(tunnelConfig);
    } catch (DeadObjectException e) {
      // The VPN process died after we got the proxy. Retry once on a fresh binding.
      LOG.log(Level.WARNING, "VPN service died, retrying the tunnel start", e);
      markVpnServiceDead();
      return getVpnTunnelService().startTunnel(tunnelConfig);
    }
  }

  // Returns whether the VPN service is running a particular tunnel instance.
  private boolean isTunnelActive(final String tunnelId) {
    // Don't wait for a binding: this is a status poll, and no service means no active tunnel.
    final IVpnTunnelService service;
    synchronized (serviceLock) {
      service = vpnTunnelService;
    }
    if (service == null) {
      return false;
    }
    try {
      return service.isTunnelActive(tunnelId);
    } catch (DeadObjectException e) {
      markVpnServiceDead();
    } catch (Exception e) {
      LOG.log(Level.SEVERE,
          String.format(Locale.ROOT, "Failed to determine if tunnel is active: %s", tunnelId), e);
    }
    return false;
  }

  // VPN service binding

  /**
   * Returns the VPN service, waiting for it to connect if necessary.
   *
   * If the system does not (re)connect the binding within SERVICE_BIND_TIMEOUT_MS, or reported it
   * dead, it is not going to: rebind from scratch, the way a force stop would, and wait for that.
   * Must not be called on the main thread, which delivers the connection.
   *
   * @throws RemoteException if the service does not connect even after rebinding.
   */
  private IVpnTunnelService getVpnTunnelService() throws RemoteException {
    IVpnTunnelService service = awaitVpnTunnelService(SERVICE_BIND_TIMEOUT_MS);
    if (service != null) {
      return service;
    }
    LOG.warning("VPN service is not connected, rebinding");
    rebindVpnService();
    service = awaitVpnTunnelService(SERVICE_BIND_TIMEOUT_MS);
    if (service == null) {
      throw new RemoteException("VPN service is unavailable");
    }
    return service;
  }

  // Waits up to timeoutMs for a live service. Returns null right away if the binding is broken,
  // since no connection will arrive on it.
  @Nullable
  private IVpnTunnelService awaitVpnTunnelService(long timeoutMs) {
    final long deadline = System.currentTimeMillis() + timeoutMs;
    synchronized (serviceLock) {
      while (true) {
        if (vpnTunnelService != null && !vpnTunnelService.asBinder().isBinderAlive()) {
          // The proxy outlived the VPN process without onServiceDisconnected clearing it.
          vpnTunnelService = null;
          bindingBroken = true;
        }
        if (vpnTunnelService != null || bindingBroken) {
          return vpnTunnelService;
        }
        final long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          return null;
        }
        try {
          serviceLock.wait(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
  }

  // Clears the service proxy if its process is gone, so the next action rebinds.
  private void markVpnServiceDead() {
    synchronized (serviceLock) {
      if (vpnTunnelService == null || !vpnTunnelService.asBinder().isBinderAlive()) {
        vpnTunnelService = null;
        bindingBroken = true;
      }
    }
  }

  /**
   * Replaces the binding to the VPN service with a fresh one. Unbinding first matters: while the
   * system holds a dead service waiting for a restart it will not perform, binding again on top of
   * the old binding does nothing. One unbind also drops every binding onServiceDisconnected stacked
   * up on this connection.
   *
   * Only call this when the service is known to be gone or unresponsive: if the old binding is the
   * only thing keeping a live service running, unbinding destroys it and its tunnel.
   */
  private void rebindVpnService() {
    synchronized (serviceLock) {
      vpnTunnelService = null;
      bindingBroken = false;
    }
    final Context context = getBaseContext();
    try {
      context.unbindService(vpnServiceConnection);
    } catch (IllegalArgumentException e) {
      // Not bound.
    }
    final Intent intent = new Intent(context, VpnTunnelService.class);
    intent.putExtra(MessageData.ERROR_REPORTING_API_KEY.value, errorReportingApiKey);
    context.bindService(intent, vpnServiceConnection, Context.BIND_AUTO_CREATE);
  }

  // Broadcasts

  private VpnTunnelBroadcastReceiver vpnTunnelBroadcastReceiver =
      new VpnTunnelBroadcastReceiver(OutlinePlugin.this);

  // Receiver to forward VPN service broadcasts to the WebView when the tunnel status changes.
  private static class VpnTunnelBroadcastReceiver extends BroadcastReceiver {
    private final OutlinePlugin outlinePlugin;

    public VpnTunnelBroadcastReceiver(OutlinePlugin outlinePlugin) {
      this.outlinePlugin = outlinePlugin;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      final String tunnelId = intent.getStringExtra(MessageData.TUNNEL_ID.value);
      if (tunnelId == null) {
        LOG.warning("Tunnel status broadcast missing tunnel ID");
        return;
      }
      if (outlinePlugin.statusCallback == null) {
        LOG.warning(String.format(
            Locale.ROOT, "No status callback registered with the Android OutlinePlugin"));
        return;
      }
      int status = intent.getIntExtra(MessageData.PAYLOAD.value, TunnelStatus.INVALID.value);
      LOG.fine(String.format(Locale.ROOT, "VPN connectivity changed: %s, %d", tunnelId, status));

      JSONObject jsonResponse = new JSONObject();
      try {
        jsonResponse.put("id", tunnelId);
        jsonResponse.put("status", status);
      } catch (JSONException e) {
        LOG.warning("Failed to build JSON response");
        return;
      }

      PluginResult result = new PluginResult(PluginResult.Status.OK, jsonResponse);
      // Keep the tunnel status callback so it can be called multiple times.
      result.setKeepCallback(true);
      outlinePlugin.statusCallback.sendPluginResult(result);
    }
  };

  // Helpers

  private Context getBaseContext() {
    return this.cordova.getActivity().getApplicationContext();
  }

  private void sendActionResult(final CallbackContext callback, @Nullable PlatformError error) {
    sendActionResult(callback, Errors.toDetailedJsonError(error));
  }

  private void sendActionResult(final CallbackContext callback, @Nullable DetailedJsonError error) {
    if (error == null) {
      callback.success();
    } else {
      callback.error(error.errorJson);
    }
  }
}
