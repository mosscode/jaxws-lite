/**
 * Copyright (C) 2013, Moss Computing Inc.
 *
 * This file is part of jaxws-lite.
 *
 * jaxws-lite is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * jaxws-lite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with jaxws-lite; see the file COPYING.  If not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obligated to do so.  If you do not wish to do so, delete this
 * exception statement from your version.
 */
package com.moss.jaxwslite;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpConnectionManager;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ServiceFactory {
	
	private static final ServiceFactory defaultFactory = new ServiceFactory();
	
	public static ServiceFactory defaultInstance() {
		return defaultFactory;
	}
	
	public static <T> T createDefault(String url, String namespace, Class<T> iface) {
		
		URL u;
		try {
			u = new URL(url);
		}
		catch (MalformedURLException ex) {
			throw new RuntimeException(ex);
		}
		
		return defaultFactory.create(u, namespace, iface);
	}
	public static <T> T createDefault(String url, QName qname, Class<T> iface) {
		try {
			return defaultFactory.create(new URL(url), qname.getNamespaceURI(), iface);
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}
	public static <T> T createDefault(URL url, QName qname, Class<T> iface) {
		return defaultFactory.create(url, qname.getNamespaceURI(), iface);
	}
	public static <T> T createDefault(URL url, String namespace, Class<T> iface) {
		return defaultFactory.create(url, namespace, iface);
	}

	private final Log log = LogFactory.getLog(getClass());
	private final HttpClient client;
	private final Map<Class<?>, ServiceType> typesCache = new HashMap<Class<?>, ServiceType>();
	private final Map<String, Service> servicesCache = new HashMap<String, Service>();
	
	public ServiceFactory() {
		
		HttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
      	client = new HttpClient(connectionManager);
      	
      	setConnectionTimeout(30000);
	}
	
	public synchronized void setConnectionTimeout(int timeout) {
		
		HttpConnectionManager manager = client.getHttpConnectionManager();
		HttpConnectionManagerParams p = manager.getParams();
		
		p.setConnectionTimeout(timeout);
		
		manager.setParams(p);
	}
	
	@SuppressWarnings("unchecked")
	public synchronized <T> T create(URL url, String namespace, Class<T> iface) {
		try {
			String key = url.toString();
			
			Service service = servicesCache.get(key);
			
			if (service != null) {
				
				T proxy = (T)service.proxy();
				
				if (log.isDebugEnabled()) {
					log.debug("Re-using service resources: " + key + " -> proxy(" + proxy + ")");
				}
				
				return (T)service.proxy();
			}
			else {
				
				ServiceType serviceType = typesCache.get(iface);
				
				if (serviceType != null) {
					
					if (log.isDebugEnabled()) {
						log.debug("Initializing service resources: " + key);
					}
					
					service = new Service(client, key, serviceType);
					servicesCache.put(key, service);
					
					return (T)service.proxy();
				}
				else {
					
					if (log.isDebugEnabled()) {
						log.debug("Initializing service type resources: " + iface.getName());
					}
					
					serviceType = new ServiceType(iface, namespace);
					typesCache.put(iface, serviceType);
					
					if (log.isDebugEnabled()) {
						log.debug("Initializing service resources: " + url);
					}
					
					service = new Service(client, key, serviceType);
					servicesCache.put(key, service);
					
					return (T)service.proxy();					
				}
			}
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public synchronized void initType(String namespace, Class<?> iface) {
		try {
			ServiceType serviceType = typesCache.get(iface);

			if (serviceType == null) {

				if (log.isDebugEnabled()) {
					log.debug("Initializing service type resources: " + iface.getName());
				}

				serviceType = new ServiceType(iface, namespace);
				typesCache.put(iface, serviceType);
			}
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}
