/** Copyright 2011 Dmitriy Rogatkin, All rights reserved.
 *  $Id: TJWSServ.java,v 1.15 2012/09/15 17:47:27 dmitriy Exp $
 */
package rogatkin.mobile.web;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import javax.servlet.Servlet;

import rogatkin.web.WarRoller;
import rogatkin.web.WebApp;
import rogatkin.web.WebAppServlet;
import Acme.Utils;
import Acme.Serve.FileServlet;
import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Android TJWS server service
 * 
 * @author drogatki
 * 
 */
public class TJWSServ extends Service {
	public enum OperCode {
		info, stop, remove, deploy
	}

	static final String SERVICE_NAME = "TJWS";
	public static final String DEPLOYMENTDIR = "atjeews/webapps";

	public static final String LOGDIR = "atjeews/log";

	public static final String KEYSTORE_PATH = "atjeews/key/keystore";
	public static final int ST_RUN = 1;
	public static final int ST_STOP = 0;
	public static final int ST_ERR = -1;
	protected AndroidServ srv;
	protected Config config;

	protected ArrayList<String> servletsList;
	protected PrintStream logStream;
	public File deployDir;

	private int status;

	@Override
	public IBinder onBind(Intent intent) {
		if (Main.DEBUG)
			Log.d(SERVICE_NAME, "Binding from " + intent.getClass().getName());
		initServ();
		
		return mBinder;
	}

	private final RCServ.Stub mBinder = new RCServ.Stub() {

		
		public String start() throws RemoteException {
			String result = updateNetworkSettings();
			startServ();
			return result;
		}

		
		public void stop() throws RemoteException {
			stopServ();
		}

		
		public int getStatus() throws RemoteException {
			return status;
		}

	
		public void logging(boolean enable) throws RemoteException {
			srv.setAccessLogged(enable);
		}

		
		public List<String> getApps() throws RemoteException {
			return servletsList;
		}


		public String deployApp(String url) throws RemoteException {
			return deployAppFrom(url);
		}

	
		public List<String> rescanApps() throws RemoteException {
			scanDeployments();
			updateServletsList();
			return servletsList;
		}


		public String getAppInfo(String name) throws RemoteException {
			return (String) doAppOper(OperCode.info, name);
		}

	
		public List<String> stopApp(String name) throws RemoteException {
			return (List<String>) doAppOper(OperCode.stop, name);
		}

		
		public void removeApp(String name) throws RemoteException {
			doAppOper(OperCode.remove, name);
		}

		
		public List<String> redeployApp(String name) throws RemoteException {
			return (List<String>) doAppOper(OperCode.deploy, name);
		}
	};

	@Override
	public void onDestroy() {
		stopServ(); // just in case
		srv.destroyAllServlets();
		if (logStream != System.out && logStream != null)
			logStream.close();
		super.onDestroy();
	}

	private void stopServ() {
		if (status != ST_STOP)
			srv.notifyStop();
	}

	private void startServ() {
		if (status != ST_RUN) {
			new Thread() {
				@Override
				public void run() {
					status = ST_RUN;
					int code = 0;
					try {
						code = srv.serve();
						if (Main.DEBUG)
							Log.d(SERVICE_NAME, "Serve terminated with :"+code);
					} finally {
						status = code==0?ST_STOP:ST_ERR;
						// TODO find out how notify client
					}
				}
			}.start();
		}
	}

	protected void initServ() {
		if (srv != null) {
			if (Main.DEBUG)
				Log.d(SERVICE_NAME, "Serve is already initialized");
			return;
		}
		if (config == null)
			config = new Config();
		config.load(this);
		initLogging();
		// setting properties for the server, and exchangeable Acceptors
		Properties properties = new Properties();
		properties.setProperty(Acme.Serve.Serve.ARG_NOHUP, "nohup");
		// properties.put(Acme.Serve.Serve.ARG_KEEPALIVE, Boolean.FALSE);
		// log properties
		properties.setProperty(Acme.Serve.Serve.ARG_ACCESS_LOG_FMT,
				"{0} {2} [{3,date,dd/MMM/yy:HH:mm:ss Z}] \"{4} {5} {6}\" {7,number,#}");
		// //// JSP /////
		properties.setProperty(Acme.Serve.Serve.ARG_JSP, "org.apache.jasper.servlet.JspServlet");
		properties.setProperty("org.apache.jasper.servlet.JspServlet.classpath", "%classpath%");
		properties.setProperty("org.apache.jasper.servlet.JspServlet.scratchdir", "%deploydir%/META-INF/jsp-classes");
		// //////////
		srv = new AndroidServ(properties, logStream, (Object)this);
		updateRealm();
		updateWWWServlet();
		// add settings servlet
		srv.addServlet("/settings", new Settings(this));
		System.setProperty(WebAppServlet.WAR_NAME_AS_CONTEXTPATH, "yes");
		// set dex class loader
		System.setProperty(WebApp.DEF_WEBAPP_CLASSLOADER, AndroidClassLoader.class.getName()); // "rogatkin.mobile.web.AndroidClassLoader"
		initDeployDirectory();
		srv.deployApps();
		updateServletsList();
	}

	protected void initLogging() {
		if (logStream != null)
			return;
		File logDir = new File(Environment.getExternalStorageDirectory(), LOGDIR);
		if (logDir.exists() && logDir.isDirectory() || logDir.mkdirs()) {
			try {
				logStream = new PrintStream(new File(logDir, "access-" + System.currentTimeMillis() + ".log"), "UTF-8");
			} catch (Exception e) {
				if (Main.DEBUG)
					Log.e(SERVICE_NAME, "Can't create log file", e);
			}
		}
		if (logStream == null)
			logStream = System.out;
		else
			System.setErr(logStream);
	}

	protected void initDeployDirectory() {
		if (deployDir != null && deployDir.exists())
			return;

		if (config.useSD) {
			deployDir = new File(Environment.getExternalStorageDirectory(), DEPLOYMENTDIR);
			if (deployDir.exists() || deployDir.mkdirs())
				System.setProperty(WebApp.DEF_WEBAPP_AUTODEPLOY_DIR, deployDir.getPath());
			else
				config.useSD = false;
		}
		if (config.useSD == false) {
			deployDir = new File(System.getProperty(Config.APP_HOME), DEPLOYMENTDIR);
			if (deployDir.exists() == false && deployDir.mkdir() == false) {
				if (Main.DEBUG)
					Log.e(SERVICE_NAME, "Can't establish web apps deployment directory");
				deployDir = new File("/sdcard", DEPLOYMENTDIR);
			}
		}
	}

	protected void updateWWWServlet() {
		Servlet rootServlet = srv.getServlet("/*");
		if (Main.DEBUG)
			Log.d(SERVICE_NAME, "Root app :" + config.rootApp + ", servlet / " + rootServlet);
		if ("/".equals(config.rootApp)) {

			Acme.Serve.Serve.PathTreeDictionary aliases = new Acme.Serve.Serve.PathTreeDictionary();
			aliases.put("/*", new File(config.wwwFolder));
			srv.setMappingTable(aliases);
			if (rootServlet instanceof FileServlet == false) {
				if (rootServlet != null)
					srv.unloadServlet(rootServlet);
				srv.addDefaultServlets(null); // optional file servlet
			}
		} else {
			if (rootServlet != null) {
				srv.unloadServlet(rootServlet);
				rootServlet.destroy();
				srv.unloadSessions(rootServlet.getServletConfig().getServletContext());
			}
			if (config.rootApp != null) {
				System.setProperty(WebAppServlet.WAR_DEPLOY_IN_ROOT, config.rootApp.substring(1));
			} else {
				System.getProperties().remove(WebAppServlet.WAR_DEPLOY_IN_ROOT);
			}
		}
	}

	protected void updateRealm() {
		Acme.Serve.Serve.PathTreeDictionary realms = new Acme.Serve.Serve.PathTreeDictionary();
		if (config.password != null && config.password.length() > 0) {
			Acme.Serve.Serve.BasicAuthRealm realm;
			realms.put("/settings", realm = new Acme.Serve.Serve.BasicAuthRealm(Main.APP_NAME));
			realm.put("", config.password);
		}
		srv.setRealms(realms);
	}

	void storeConfig() {
		config.store(this);
	}

	public InetAddress getLocalIpAddress() {
		try {
			if (config.bindAddr != null)
				return InetAddress.getByName(config.bindAddr);
		} catch (UnknownHostException e) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, "Can't resolve :" + config.bindAddr + " " + e.toString());
			return null;
		}
		return getLookbackAddress(); //getNonLookupAddress();
	}
	
	public static InetAddress getLookbackAddress() {
		InetAddress result = null;
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (inetAddress.isLoopbackAddress()) {
						if (inetAddress.isSiteLocalAddress() == false)
							return inetAddress;
						result = inetAddress;
					}
				}
			}
		} catch (SocketException ex) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, ex.toString());
		}
		return result;
	}
	
	public static InetAddress getNonLookupAddress() {
		InetAddress result = null;
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						if (inetAddress.isSiteLocalAddress() == false)
							return inetAddress;
						result = inetAddress;
					}
				}
			}
		} catch (SocketException ex) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, ex.toString());
		}
		return result;
	}

	String updateNetworkSettings() {
		config.load(this);
		// port
		srv.arguments.put(Acme.Serve.Serve.ARG_PORT, config.port);
		// SSL
		if (config.ssl) {
			srv.arguments.put(Acme.Serve.Serve.ARG_ACCEPTOR_CLASS, "Acme.Serve.SSLAcceptor");
			srv.arguments.put(Acme.Serve.SSLAcceptor.ARG_KEYSTOREFILE,
					new File(Environment.getExternalStorageDirectory(), KEYSTORE_PATH).getPath());
			srv.arguments.put(Acme.Serve.SSLAcceptor.ARG_KEYSTOREPASS, "changeme");
			srv.arguments.put(Acme.Serve.SSLAcceptor.ARG_KEYSTORETYPE, "BKS");
		} else
			srv.arguments.remove(Acme.Serve.Serve.ARG_ACCEPTOR_CLASS);
		srv.setAccessLogged(config.logEnabled);
		// bind address
		InetAddress iadr = getLocalIpAddress();
		if (iadr != null) {
			String canonicalAddr = iadr.getCanonicalHostName();
			if (canonicalAddr != null && "null".equals(canonicalAddr) == false) { // Android bug
				if (iadr.isAnyLocalAddress() == false) {
					srv.arguments.put(Acme.Serve.Serve.ARG_BINDADDRESS, iadr.getHostAddress());
					return canonicalAddr;
				} else {
					srv.arguments.remove(Acme.Serve.Serve.ARG_BINDADDRESS);
					iadr = getNonLookupAddress();
					if (iadr != null)
						return iadr.getHostAddress();
				}
			}
		}
		srv.arguments.remove(Acme.Serve.Serve.ARG_BINDADDRESS);
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			return "::";
		}
	}

	protected String deployAppFrom(String u) {
		FileOutputStream fos = null;
		InputStream cis = null;
		try {
			// TODO consider using DownloadManager
			URL url = new URL(u);
			String appFile = url.getFile();
			int sp = appFile.lastIndexOf('/');
			if (sp >= 0)
				appFile = appFile.substring(sp + 1);
			File warFile;
			URLConnection ucon = url.openConnection();
			ucon.setConnectTimeout(30 * 1000);
			Utils.copyStream(cis = ucon.getInputStream(), fos = new FileOutputStream(warFile = new File(deployDir,
					appFile)), 1024 * 1024 * 512);
			if (appFile.endsWith(WarRoller.DEPLOY_ARCH_EXT) == false
					|| appFile.length() <= WarRoller.DEPLOY_ARCH_EXT.length()) {
				if (Main.DEBUG)
					Log.e(SERVICE_NAME, " Invalid extension for web archive file: " + appFile
						+ ", it is stored but not deployed");
				return "Invalid extension for web archive file: " + appFile;
			}
			redeploy(appFile.substring(0, appFile.length() - WarRoller.DEPLOY_ARCH_EXT.length()));
			if (Main.DEBUG)
				Log.d(SERVICE_NAME, appFile + " has been deployed");
			// update list
			updateServletsList();
		} catch (IOException ioe) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, "Could't deploy " + u, ioe);
			return "" + ioe;
		} finally {
			if (fos != null)
				try {
					fos.close();
				} catch (IOException e) {
				}
			if (cis != null)
				try {
					cis.close();
				} catch (IOException e) {
				}
		}
		return null;
	}

	void scanDeployments() {
		deployDir.listFiles(new FileFilter() {
			public boolean accept(File pathname) {
				String fileName = pathname.getName();
				String appName = pathname.isFile() && fileName.toLowerCase().endsWith(WarRoller.DEPLOY_ARCH_EXT) ? fileName
						.substring(0, fileName.length() - WarRoller.DEPLOY_ARCH_EXT.length()) : null;
				if (appName != null && findServlet("/" + appName + "/*") == false) {
					srv.getDeployer().deployWar(pathname, new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET));
					if (Main.DEBUG)
						Log.d(SERVICE_NAME, "Found a not deployed app  " + appName);
					return true;
				}
				return false;
			}
		});
		// scan for already deployed but not launched
		File[] deployedFiles = new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET).listFiles();
		if (deployedFiles == null) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, "Invaid deploy directory: " + new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET));
			return;
		}

		for (File deployedFile : deployedFiles) {
			if (deployedFile.isDirectory() && findServlet("/" + deployedFile.getName() + "/*") == false) {
				if (Main.DEBUG)
					Log.d(SERVICE_NAME, "Found not deployed app  " + deployedFile);
				srv.deployApp(deployedFile.getName());
			}
		}
	}

	protected Object doAppOper(OperCode oc, String appName) {
		Servlet servlet = srv.getServlet(appName);
		if (servlet == null) {
			if (Main.DEBUG)
				Log.e(SERVICE_NAME, "No servlet found for " + appName);
			return null;
		}
		WebAppServlet appServlet = servlet instanceof WebAppServlet ? (WebAppServlet) servlet : null;
		switch (oc) {
		case info:
			return servlet.getServletInfo();
		case deploy:
			if (appServlet != null) {
				redeploy(appName.substring(0, appName.length() - "/*".length()));
				updateServletsList();
			}
			return servletsList;
		case remove:
			if (appServlet != null) {
				if (appName.endsWith("/*"))
					appName = appName.substring(1, appName.length()-2);
				else if (appName.endsWith("/"))
					appName = appName.substring(1, appName.length()-1);
				File appWar = new File(deployDir, appName + WarRoller.DEPLOY_ARCH_EXT);
				if (appWar.delete() == false)
					if (Main.DEBUG)
						Log.e(SERVICE_NAME, "File can't be deleted " + appWar);
				else {
					File appFile = new File(new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET), appName);
					if (deleteRecursively(appFile) == false) {
						if (Main.DEBUG)
							Log.e(SERVICE_NAME, "File can't be deleted " + appFile);
					}
				}
			} else if (Main.DEBUG)
				Log.e(SERVICE_NAME, "Can't find app " + appName+" to remove");
			return null;
		case stop:
			servlet = srv.unloadServlet(servlet);
			if (servlet != null) {
				servlet.destroy();
				srv.unloadSessions(servlet.getServletConfig().getServletContext());
			} else if (Main.DEBUG)
				Log.e(SERVICE_NAME, "Couldn't unload servlet for " + appName);
			updateServletsList();
			return servletsList;
		}
		return null;
	}

	protected void redeploy(String appName) {
		Servlet servlet = srv.getServlet(appName + "/*");
		// TODO use this code for context menu reload to avoid crash
		if (servlet != null) {
			servlet = srv.unloadServlet(servlet);
			if (servlet != null && servlet instanceof WebAppServlet) {
				servlet.destroy();
				srv.unloadSessions(servlet.getServletConfig().getServletContext());
				File dexCacheDir = new File(new File(new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET), appName),
						"META-INF/DEX/" + SERVICE_NAME);
				// Log.d(APP_NAME, ""+dexCacheDir);
				if (dexCacheDir.exists() && dexCacheDir.isAbsolute()) {
					deleteRecursively(dexCacheDir);
				}
			}
		}
		srv.getDeployer().deployWar(new File(deployDir, appName + WarRoller.DEPLOY_ARCH_EXT),
				new File(deployDir, WarRoller.DEPLOYMENT_DIR_TARGET));
		srv.deployApp(appName);
	}

	private boolean deleteRecursively(File topf) {
		for (File curf : topf.listFiles()) {
			if (curf.isFile()) {
				if (curf.delete() == false)
					return false;
			} else if (curf.isDirectory()) {
				if (deleteRecursively(curf) == false)
					return false;
			} else
				return false;
		}
		return topf.delete();
	}

	private void updateServletsList() {
		if (servletsList == null)
			servletsList = new ArrayList<String>();
		else
			servletsList.clear();
		Enumeration servlets = srv.getServletNames();
		while (servlets.hasMoreElements()) {
			servletsList.add((String) servlets.nextElement());
		}
	}

	private boolean findServlet(String servletName) {
		for (String curName : servletsList) {
			if (curName.equals(servletName))
				return true;
		}
		return false;
	}

	class AndroidServ extends Acme.Serve.Serve {
		private WarRoller deployer;

		public AndroidServ(Properties arguments, PrintStream logStream, Object runtime) {
			super(arguments, logStream);
			WebAppServlet.setRuntimeEnv(runtime); // provide servlet context Android environment access
		}

		// Overriding method for public access
		@Override
		public void setMappingTable(PathTreeDictionary mappingtable) {
			super.setMappingTable(mappingtable);
		}

		@Override
		protected void setRealms(PathTreeDictionary realms) {
			super.setRealms(realms);
		}

		public synchronized void deployApps() {
			if (deployer == null)
				deployer = new WarRoller();
			try {
				deployer.deploy(this);
			} catch (Throwable t) {
				if (t instanceof ThreadDeath)
					throw (ThreadDeath) t;
				if (Main.DEBUG)
					Log.e(SERVICE_NAME, "Unexpected problem in deploying apps", t);
			}
		}

		public synchronized boolean deployApp(String appName) {
			// deployer must be not null
			try {
				WebAppServlet webAppServlet = WebAppServlet.create(new File(new File(deployDir,
						WarRoller.DEPLOYMENT_DIR_TARGET), appName), appName, this, null);
				addServlet(webAppServlet.getContextPath() + "/*", webAppServlet, null);
				return true;
			} catch (Throwable t) {
				if (t instanceof ThreadDeath)
					throw (ThreadDeath) t;
				if (Main.DEBUG)
					Log.e(SERVICE_NAME, "Problem in deployment " + appName, t);
			}
			return false;
		}

		WarRoller getDeployer() {
			return deployer;
		}

		protected void setAccessLogged(boolean on) {
			if (on)
				arguments.put(ARG_LOG_OPTIONS, "L");
			else
				arguments.remove(ARG_LOG_OPTIONS);
			setAccessLogged();
		}
	}
}