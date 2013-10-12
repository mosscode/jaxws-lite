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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.moss.jaxbhelper.JAXBHelper;

public class Service implements InvocationHandler {
	
	private final Log log = LogFactory.getLog(this.getClass());

	private final HttpClient client;
	private final String url;
	private final ServiceType type;
	private final Object proxy;
	
	public Service(HttpClient client, String url, ServiceType type) {
		
		if (url.endsWith("?wsdl")) {
			
			this.url = url.substring(0, url.length() - 5);
			
			if (log.isDebugEnabled()) {
				log.debug("Excluding ?wsdl parameter from url: " + url + " -> " + this.url);
			}
		}
		else {
			this.url = url;
		}
		
		this.client = client;
		this.type = type;
		
		ClassLoader cl = this.getClass().getClassLoader();
		Class[] interfaces = new Class[]{ type.iface() };
		proxy = Proxy.newProxyInstance(cl, interfaces, this);
	}
	
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		
		if (args == null) {
			args = new Object[0];
		}
		
		if (method.getName().equals("toString") && args.length == 0) {
			return url.toString();
		}
		
		PostMethod post = new PostMethod(url.toString());
		
		final byte[] requestContent = type.request(method, args);
		
		RequestEntity requestEntity = new ByteArrayRequestEntity(requestContent);
		post.setRequestEntity(requestEntity);
		post.addRequestHeader("Content-Type", "text/xml");
		
		if (log.isDebugEnabled()) {
        	new Thread(){
        		public void run() {
        			try {
        				setPriority(MIN_PRIORITY);
        				
        				ByteArrayOutputStream out = new ByteArrayOutputStream();
        				JAXBHelper.beautify(new ByteArrayInputStream(requestContent), out);
        				
        				StringBuilder sb = new StringBuilder();
        				sb.append("Sending post: ").append(url).append("\n");
        				sb.append(new String(out.toByteArray()));
        				
        				log.debug(sb.toString());
        			} 
        			catch (Exception e) {
        				throw new RuntimeException(e);
        			}
        		}
        	}.start();
		}
		
		try {

			int responseCode = client.executeMethod(post);
			boolean fault = responseCode != 200;

			final byte[] responseContent;
			{
				InputStream in = post.getResponseBodyAsStream();
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buffer = new byte[1024 * 10]; //10k buffer
				for(int numRead = in.read(buffer); numRead!=-1; numRead = in.read(buffer)){
					out.write(buffer, 0, numRead);
				}
				responseContent = out.toByteArray();
			}

			if (log.isDebugEnabled()) {
				new Thread(){
					public void run() {
						try {
							setPriority(MIN_PRIORITY);

							ByteArrayOutputStream out = new ByteArrayOutputStream();
							JAXBHelper.beautify(new ByteArrayInputStream(responseContent), out);

							StringBuilder sb = new StringBuilder();
							sb.append("Receiving post response: ").append(url).append("\n");
							sb.append(new String(out.toByteArray()));

							log.debug(sb.toString());
						} 
						catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
				}.start();
			}

			Object response = type.response(method, responseContent, fault);

			if (response instanceof Exception) {
				throw (Exception)response;
			}
			else {
				return response;
			}
		}
		finally {
			post.releaseConnection();
		}
	}
	
	public String url() {
		return url;
	}
	
	public ServiceType type() {
		return type;
	}
	
	public Object proxy() {
		return proxy;
	}
}
