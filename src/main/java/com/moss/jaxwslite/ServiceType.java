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

import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

public class ServiceType {
	
	private final Class<?> iface;
	private final String namespace;
	private final Map<Method, MethodInfo> methodInfo;
	private final JAXBContext jaxbContext;

	public ServiceType(Class<?> iface, String namespace) throws Exception {
		
		this.iface = iface;
		this.namespace = namespace;
		
		try {
			Set<Class> classes = new HashSet<Class>();
			methodInfo = new HashMap<Method, MethodInfo>();
			
			for (Method method : iface.getMethods()) {
				
				MethodInfo info = new MethodInfo(method);
				methodInfo.put(method, info);
				
				classes.add(info.requestClass);
				classes.add(info.responseClass);
				classes.addAll(methodTypes(method));
			}
			
			jaxbContext = JAXBContext.newInstance(classes.toArray(new Class[0]));
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public Class<?> iface() {
		return iface;
	}
	
	public byte[] request(Method method, Object[] args) throws Exception {
		
		JAXBElement element;
		{
			MethodInfo info = methodInfo.get(method);
			Object wrapper = info.newRequest(args);
			QName qname = new QName(namespace, method.getName());
			element = new JAXBElement(qname, wrapper.getClass(), null, wrapper);
		}
		
		final String envelopeNs = "http://schemas.xmlsoap.org/soap/envelope/";
		final String envelopePrefix = "soap";
		
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		XMLOutputFactory f = XMLOutputFactory.newInstance();
		XMLEventWriter writer = f.createXMLEventWriter(out);
		XMLEventFactory ef = XMLEventFactory.newInstance();
		
		writer.add(ef.createStartDocument("UTF-8", "1.0"));
		writer.add(ef.createStartElement(envelopePrefix, envelopeNs, "Envelope"));
		writer.add(ef.createNamespace(envelopePrefix, envelopeNs));
		writer.add(ef.createStartElement(envelopePrefix, envelopeNs, "Body"));

		Marshaller m = jaxbContext.createMarshaller();
		m.setProperty(Marshaller.JAXB_FRAGMENT, true);
		m.marshal(element, writer);
		
		writer.add(ef.createEndElement(envelopePrefix, envelopeNs, "Body"));
		writer.add(ef.createEndElement(envelopePrefix, envelopeNs, "Envelope"));
		writer.add(ef.createEndDocument());
		writer.close();
		
		String results = new String(out.toByteArray(), "UTF8");
		results = results.replaceAll("xsi:type=\"", "xsi:type=\"ns2:");
		
		return results.getBytes("UTF8");
	}
	
	public Object response(Method method, byte[] responseContent, boolean fault) throws Exception {
		
		String results = new String(responseContent, "UTF8"); 
		results = results.replaceAll("xsi:type=\"ns2:", "xsi:type=\"");
		Reader r = new StringReader(results);
		
		MethodInfo info = methodInfo.get(method);
		
		XMLInputFactory f = XMLInputFactory.newInstance();
		XMLEventReader reader = f.createXMLEventReader(r);
		Object response = null;
		
		if (!fault) {

			while (reader.hasNext()) {

				XMLEvent e = reader.nextEvent();

				if (! (e instanceof StartElement)) {
					continue;
				}

				StartElement start = (StartElement)e;
				String name = start.getName().getLocalPart().toLowerCase();

				if (name.equals("body")) {
					Unmarshaller u = jaxbContext.createUnmarshaller();
					Object wrapper = u.unmarshal(reader, info.responseClass).getValue();
					
					if (info.responseGetter == null) {
						response = null;
					}
					else {
						response = info.responseGetter.invoke(wrapper);
						
						if (response == null) {
							Class t = info.responseGetter.getReturnType(); 
							if (List.class.isAssignableFrom(t)) {
								response = new ArrayList();
							}
							else if (Set.class.isAssignableFrom(t)) {
								response = new HashSet();
							}
						}
					}
					break;
				}
			}
		}
		else {
			
			while (reader.hasNext()) {
			
				XMLEvent e = reader.nextEvent();

				if (! (e instanceof StartElement)) {
					continue;
				}

				StartElement start = (StartElement)e;
				String name = start.getName().getLocalPart().toLowerCase();

				if (name.equals("fault")) {

					String currentElement = null;

					String faultString = null;
					Class<?> exceptionType = null;

					while (true) {
						e = reader.nextEvent();

						if (e instanceof StartElement) {
							StartElement s = (StartElement)e;
							currentElement = s.getName().getLocalPart();

							if (currentElement.equals("detail")) {
								e = reader.nextTag();
								String exceptionName = ((StartElement)e).getName().getLocalPart();
								exceptionType = info.declaredExceptions.get(exceptionName);
							}
						}
						else if (e instanceof Characters) {
							Characters c = (Characters)e;
							String data = c.getData();

							if (currentElement.equals("faultstring")) {
								faultString = data;
							}
							else {
								continue;
							}
						}
						else if (e instanceof EndElement) {
							EndElement end = (EndElement)e;
							String endName = end.getName().getLocalPart().toLowerCase();
							if (endName.equals("fault")) {
								break;
							}
							else {
								currentElement = null;
								continue;
							}
						}
					}
					
					if (exceptionType != null) {
						Constructor c = exceptionType.getConstructor(new Class[]{ String.class });
						response = c.newInstance(faultString);
						break;
					}
					else {
						if (faultString != null) {
							response = new RuntimeException(faultString.trim());
						}
						else {
							response = new RuntimeException("SOAP Fault received");
						}
					}
				}
			}
		}
		
		reader.close();
		
		return response;
		
		/*
		 * Unmarshall the response. Determine if the result is
		 * 1) a runtime fault (throw runtime exception)
		 * 2) an application fault (throw normal exception)
		 * 3) a normal response  (return response)
		 */
	}
	
	private Set<Class> methodTypes(Method method) {
		
		Set<Class> types = new HashSet<Class>();
		
		for (Class type : method.getParameterTypes()) {
			types.addAll(fieldTypes(type));
		}
		
		types.addAll(fieldTypes(method.getReturnType()));
		
		return types;
	}
	
	private Set<Class> fieldTypes(Class clazz) {
		
		Set<Class> types = new HashSet<Class>();
		
		for (Field field : clazz.getDeclaredFields()) {
			Class type = field.getType();
			
			if (type.isInterface()) {
				continue;
			}
			
			if (type.isPrimitive()) {
				continue;
			}
			
			if (type.getName().startsWith("java")) {
				continue;
			}
			
			if (type.isArray()) {
				continue;
			}
			
			if (type.isEnum()) {
				continue;
			}
			
			if (type.isAnnotationPresent(XmlJavaTypeAdapter.class)) {
				continue;
			}
			
			try {
				type.getDeclaredConstructor();
			}
			catch (NoSuchMethodException ex) {
				continue;
			}
			
			types.add(type);
		}
		
		return types;
	}
	
	private class MethodInfo {
		final Class requestClass;
		final Method[] requestSetters;
		final Class responseClass;
		final Method responseGetter;
		final Map<String, Class> declaredExceptions;
		
		public MethodInfo(Method method) throws Exception {
			this.requestClass = requestClass(method);
			this.requestSetters = setters(requestClass);
			this.responseClass = responseClass(method);
			
			Method ret;
			try {
				 ret = responseClass.getMethod("getReturn");
			}
			catch(NoSuchMethodException ex) {
				ret = null;
			}
			this.responseGetter = ret;
			
			declaredExceptions = new HashMap<String, Class>();
			for (Class<?> exceptionType : method.getExceptionTypes()) {
				String name = exceptionType.getSimpleName();
				declaredExceptions.put(name, exceptionType);
			}
		}
		
		public Object newRequest(Object[] args) throws Exception {
			
			Object request = requestClass.newInstance();
			
			for (int i=0; i<args.length; i++) {
				Method setter = requestSetters[i];
				Object arg = args[i];
				setter.invoke(request, new Object[]{arg});
			}
			
			return request;
		}
		
		private Class requestClass(Method method) throws Exception {
			
			String simpleClassName = method.getName();
			simpleClassName = simpleClassName.substring(0, 1).toUpperCase() + simpleClassName.substring(1);
			simpleClassName = iface.getPackage().getName() + ".jaxws." + simpleClassName;
			
			Class wrapperClass = Class.forName(simpleClassName);
			return wrapperClass;
		}

		private Class responseClass(Method method) throws Exception {
			
			String simpleClassName = method.getName();
			simpleClassName = simpleClassName.substring(0, 1).toUpperCase() + simpleClassName.substring(1) + "Response";
			simpleClassName = iface.getPackage().getName() + ".jaxws." + simpleClassName;
			
			Class wrapperClass = Class.forName(simpleClassName);
			return wrapperClass;
		}
		
		private Method[] setters(Class clazz) {
			List<Method> setters = new ArrayList<Method>();
			
			for (Method method : clazz.getMethods()) {
				if (method.getName().startsWith("set")) {
					setters.add(method);
				}
			}
			
			return setters.toArray(new Method[0]);
		}
	}
}
