package io.lnk.lookup.consul;

import io.lnk.api.URI;
import io.lnk.lookup.consul.client.ConsulEcwidClient;
import io.lnk.lookup.consul.client.LnkConsulClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsulRegistry {
	protected static final Logger log = LoggerFactory.getLogger(ConsulRegistry.class);
	private LnkConsulClient client;
	private ConsulHeartbeatManager heartbeatManager;
	private int lookupInterval;

	// service local cache. key: group, value: <service interface name, url
	// list>
	private ConcurrentHashMap<String, ConcurrentHashMap<String, List<URL>>> serviceCache = new ConcurrentHashMap<String, ConcurrentHashMap<String, List<URL>>>();
	// command local cache. key: group, value: command content
	private ConcurrentHashMap<String, String> commandCache = new ConcurrentHashMap<String, String>();

	// record lookup service thread, insure each group start only one thread,
	// <group, lastConsulIndexId>
	private ConcurrentHashMap<String, Long> lookupGroupServices = new ConcurrentHashMap<String, Long>();
	// record lookup command thread, <group, command>
	private ConcurrentHashMap<String, String> lookupGroupCommands = new ConcurrentHashMap<String, String>();

	// record subscribers service callback listeners, listener was called when
	// corresponding service changes
	private ConcurrentHashMap<String, ConcurrentHashMap<URL, ServiceListener>> serviceListeners = new ConcurrentHashMap<String, ConcurrentHashMap<URL, ServiceListener>>();
	// record subscribers command callback listeners, listener was called when
	// corresponding command changes
	private ConcurrentHashMap<String, ConcurrentHashMap<URL, CommandListener>> commandListeners = new ConcurrentHashMap<String, ConcurrentHashMap<URL, CommandListener>>();
	private ThreadPoolExecutor notifyExecutor;
	final URI uri;

	public ConsulRegistry(final URI uri) {
	    this.uri = uri;
		LnkConsulClient client = new ConsulEcwidClient(uri.getHost(), uri.getPort());
		init(client);
	}

	public void init(LnkConsulClient client) {
		this.client = client;

		heartbeatManager = new ConsulHeartbeatManager(client);
		heartbeatManager.start();
		lookupInterval = ConsulConstants.DEFAULT_LOOKUP_INTERVAL;

		ArrayBlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<Runnable>(20000);
		notifyExecutor = new ThreadPoolExecutor(10, 30, 30 * 1000, TimeUnit.MILLISECONDS, workQueue);
		log.info("ConsulRegistry init finish.");
	}

	public ConcurrentHashMap<String, ConcurrentHashMap<URL, ServiceListener>> getServiceListeners() {
		return serviceListeners;
	}

	public ConcurrentHashMap<String, ConcurrentHashMap<URL, CommandListener>> getCommandListeners() {
		return commandListeners;
	}

	protected void doRegister(URL url) {
		ConsulService service = ConsulUtils.buildService(url);
		client.registerService(service);
		client.checkPass(service.getId());
		heartbeatManager.addHeartbeatServcieId(service.getId());
	}

	protected void doUnregister(URL url) {
		ConsulService service = ConsulUtils.buildService(url);
		client.unregisterService(service.getId());
		heartbeatManager.removeHeartbeatServiceId(service.getId());
	}

	protected void doAvailable(URL url) {
		if (url == null) {
			heartbeatManager.setHeartbeatOpen(true);
		} else {
			throw new UnsupportedOperationException("Command consul registry not support available by urls yet");
		}
	}

	protected void doUnavailable(URL url) {
		if (url == null) {
			heartbeatManager.setHeartbeatOpen(false);
		} else {
			throw new UnsupportedOperationException("Command consul registry not support unavailable by urls yet");
		}
	}

	protected void subscribeService(URL url, ServiceListener serviceListener) {
		addServiceListener(url, serviceListener);
		startListenerThreadIfNewService(url);
	}

	/**
	 * if new group registed, start a new lookup thread each group start a
	 * lookup thread to discover service
	 *
	 * @param url
	 */
	private void startListenerThreadIfNewService(URL url) {
		String group = url.getServiceGroup();
		if (!lookupGroupServices.containsKey(group)) {
			Long value = lookupGroupServices.putIfAbsent(group, 0L);
			if (value == null) {
				ServiceLookupThread lookupThread = new ServiceLookupThread(group);
				lookupThread.setDaemon(true);
				lookupThread.start();
			}
		}
	}

	private void addServiceListener(URL url, ServiceListener serviceListener) {
		String service = ConsulUtils.getUrlClusterInfo(url);
		ConcurrentHashMap<URL, ServiceListener> map = serviceListeners.get(service);
		if (map == null) {
			serviceListeners.putIfAbsent(service, new ConcurrentHashMap<URL, ServiceListener>());
			map = serviceListeners.get(service);
		}
		synchronized (map) {
			map.put(url, serviceListener);
		}
	}

	protected void subscribeCommand(URL url, CommandListener commandListener) {
		addCommandListener(url, commandListener);
		startListenerThreadIfNewCommand(url);
	}

	private void startListenerThreadIfNewCommand(URL url) {
		String group = url.getServiceGroup();
		if (!lookupGroupCommands.containsKey(group)) {
			String command = lookupGroupCommands.putIfAbsent(group, "");
			if (command == null) {
				CommandLookupThread lookupThread = new CommandLookupThread(group);
				lookupThread.setDaemon(true);
				lookupThread.start();
			}
		}
	}

	private void addCommandListener(URL url, CommandListener commandListener) {
		String group = url.getServiceGroup();
		ConcurrentHashMap<URL, CommandListener> map = commandListeners.get(group);
		if (map == null) {
			commandListeners.putIfAbsent(group, new ConcurrentHashMap<URL, CommandListener>());
			map = commandListeners.get(group);
		}
		synchronized (map) {
			map.put(url, commandListener);
		}
	}

	protected void unsubscribeService(URL url, ServiceListener listener) {
		ConcurrentHashMap<URL, ServiceListener> listeners = serviceListeners.get(ConsulUtils.getUrlClusterInfo(url));
		if (listeners != null) {
			synchronized (listeners) {
				listeners.remove(url);
			}
		}
	}

	protected void unsubscribeCommand(URL url, CommandListener listener) {
		ConcurrentHashMap<URL, CommandListener> listeners = commandListeners.get(url.getServiceGroup());
		if (listeners != null) {
			synchronized (listeners) {
				listeners.remove(url);
			}
		}
	}

	protected List<URL> discoverService(URL url) {
		String service = ConsulUtils.getUrlClusterInfo(url);
		String group = url.getServiceGroup();
		List<URL> serviceUrls = new ArrayList<URL>();
		ConcurrentHashMap<String, List<URL>> serviceMap = serviceCache.get(group);
		if (serviceMap == null) {
			synchronized (group.intern()) {
				serviceMap = serviceCache.get(group);
				if (serviceMap == null) {
					ConcurrentHashMap<String, List<URL>> groupUrls = lookupServiceUpdate(group);
					updateServiceCache(group, groupUrls, false);
					serviceMap = serviceCache.get(group);
				}
			}
		}
		if (serviceMap != null) {
			serviceUrls = serviceMap.get(service);
		}
		return serviceUrls;
	}

	protected String discoverCommand(URL url) {
		String group = url.getServiceGroup();
		String command = lookupCommandUpdate(group);
		updateCommandCache(group, command, false);
		return command;
	}

	private ConcurrentHashMap<String, List<URL>> lookupServiceUpdate(String group) {
		Long lastConsulIndexId = lookupGroupServices.get(group) == null ? 0 : lookupGroupServices.get(group);
		ConsulResponse<List<ConsulService>> response = lookupConsulService(group, lastConsulIndexId);
		if (response != null) {
			List<ConsulService> services = response.getValue();
			if (services != null && !services.isEmpty() && response.getConsulIndex() > lastConsulIndexId) {
				ConcurrentHashMap<String, List<URL>> groupUrls = new ConcurrentHashMap<String, List<URL>>();
				for (ConsulService service : services) {
					try {
						URL url = ConsulUtils.buildUrl(service);
						String cluster = ConsulUtils.getUrlClusterInfo(url);
						List<URL> urlList = groupUrls.get(cluster);
						if (urlList == null) {
							urlList = new ArrayList<URL>();
							groupUrls.put(cluster, urlList);
						}
						urlList.add(url);
					} catch (Exception e) {
						log.error("convert consul service to url fail! service:" + service, e);
					}
				}
				lookupGroupServices.put(group, response.getConsulIndex());
				return groupUrls;
			} else {
				log.info(group + " no need update, lastIndex:" + lastConsulIndexId);
			}
		}
		return null;
	}

	private String lookupCommandUpdate(String group) {
		String command = client.lookupCommand(group);
		lookupGroupCommands.put(group, command);
		return command;
	}

	/**
	 * directly fetch consul service data.
	 *
	 * @param serviceName
	 * @return ConsulResponse or null
	 */
	private ConsulResponse<List<ConsulService>> lookupConsulService(String serviceName, Long lastConsulIndexId) {
		ConsulResponse<List<ConsulService>> response = client
				.lookupHealthService(ConsulUtils.convertGroupToServiceName(serviceName), lastConsulIndexId);
		return response;
	}

	/**
	 * update service cache of the group. update local cache when service list
	 * changed, if need notify, notify service
	 *
	 * @param group
	 * @param groupUrls
	 * @param needNotify
	 */
	private void updateServiceCache(String group, ConcurrentHashMap<String, List<URL>> groupUrls, boolean needNotify) {
		if (groupUrls != null && !groupUrls.isEmpty()) {
			ConcurrentHashMap<String, List<URL>> groupMap = serviceCache.get(group);
			if (groupMap == null) {
				serviceCache.put(group, groupUrls);
			}
			for (Map.Entry<String, List<URL>> entry : groupUrls.entrySet()) {
				boolean change = true;
				if (groupMap != null) {
					List<URL> oldUrls = groupMap.get(entry.getKey());
					List<URL> newUrls = entry.getValue();
					if (newUrls == null || newUrls.isEmpty() || ConsulUtils.isSame(entry.getValue(), oldUrls)) {
						change = false;
					} else {
						groupMap.put(entry.getKey(), newUrls);
					}
				}
				if (change && needNotify) {
					notifyExecutor.execute(new NotifyService(entry.getKey(), entry.getValue()));
					log.info("motan service notify-service: " + entry.getKey());
					StringBuilder sb = new StringBuilder();
					for (URL url : entry.getValue()) {
						sb.append(url.getUri()).append(";");
					}
					log.info("consul notify urls:" + sb.toString());
				}
			}
		}
	}

	/**
	 * update command cache of the group. update local cache when command
	 * changed, if need notify, notify command
	 *
	 * @param group
	 * @param command
	 * @param needNotify
	 */
	private void updateCommandCache(String group, String command, boolean needNotify) {
		String oldCommand = commandCache.get(group);
		if (!command.equals(oldCommand)) {
			commandCache.put(group, command);
			if (needNotify) {
				notifyExecutor.execute(new NotifyCommand(group, command));
				log.info(String.format("command data change: group=%s, command=%s: ", group, command));
			}
		} else {
			log.info(String.format("command data not change: group=%s, command=%s: ", group, command));
		}
	}

	private class ServiceLookupThread extends Thread {
		private String group;

		public ServiceLookupThread(String group) {
			this.group = group;
		}

		@Override
		public void run() {
			log.info("start group lookup thread. lookup interval: " + lookupInterval + "ms, group: " + group);
			while (true) {
				try {
					sleep(lookupInterval);
					ConcurrentHashMap<String, List<URL>> groupUrls = lookupServiceUpdate(group);
					updateServiceCache(group, groupUrls, true);
				} catch (Throwable e) {
					log.error("group lookup thread fail!", e);
					try {
						Thread.sleep(2000);
					} catch (InterruptedException ignored) {
					}
				}
			}
		}
	}

	private class CommandLookupThread extends Thread {
		private String group;

		public CommandLookupThread(String group) {
			this.group = group;
		}

		@Override
		public void run() {
			log.info("start command lookup thread. lookup interval: " + lookupInterval + "ms, group: " + group);
			while (true) {
				try {
					sleep(lookupInterval);
					String command = lookupCommandUpdate(group);
					updateCommandCache(group, command, true);
				} catch (Throwable e) {
					log.error("group lookup thread fail!", e);
					try {
						Thread.sleep(2000);
					} catch (InterruptedException ignored) {
					}
				}
			}
		}
	}

	private class NotifyService implements Runnable {
		private String service;
		private List<URL> urls;

		public NotifyService(String service, List<URL> urls) {
			this.service = service;
			this.urls = urls;
		}

		@Override
		public void run() {
			ConcurrentHashMap<URL, ServiceListener> listeners = serviceListeners.get(service);
			if (listeners != null) {
				synchronized (listeners) {
					for (Map.Entry<URL, ServiceListener> entry : listeners.entrySet()) {
						ServiceListener serviceListener = entry.getValue();
						serviceListener.notifyService(entry.getKey(), urls);
					}
				}
			} else {
				log.debug("need not notify service:" + service);
			}
		}
	}

	private class NotifyCommand implements Runnable {
		private String group;
		private String command;

		public NotifyCommand(String group, String command) {
			this.group = group;
			this.command = command;
		}

		@Override
		public void run() {
			ConcurrentHashMap<URL, CommandListener> listeners = commandListeners.get(group);
			synchronized (listeners) {
				for (Map.Entry<URL, CommandListener> entry : listeners.entrySet()) {
					CommandListener commandListener = entry.getValue();
					commandListener.notifyCommand(entry.getKey(), command);
				}
			}
		}
	}
}
