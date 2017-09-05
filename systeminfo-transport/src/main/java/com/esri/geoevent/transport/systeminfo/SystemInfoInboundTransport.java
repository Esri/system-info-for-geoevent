/*
  Copyright 1995-2017 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  email: contracts@esri.com
*/

package com.esri.geoevent.transport.systeminfo;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Map;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;
import com.esri.ges.util.Converter;

public class SystemInfoInboundTransport extends InboundTransportBase implements Runnable
{

	private static final BundleLogger	log			= BundleLoggerFactory.getLogger(SystemInfoInboundTransport.class);

	private Thread										thread	= null;
	private double										updateIntervalSeconds = 1;

	public SystemInfoInboundTransport(TransportDefinition definition) throws ComponentException
	{
		super(definition);
	}

	@SuppressWarnings("incomplete-switch")
	public void start() throws RunningException
	{
		try
		{
			switch (getRunningState())
			{
				case STARTING:
				case STARTED:
				case STOPPING:
					return;
			}
			setRunningState(RunningState.STARTING);
			thread = new Thread(this);
			thread.start();
		}
		catch (Exception e)
		{
			log.error("UNEXPECTED_ERROR_STARTING", e);
			stop();
		}
	}

	@Override
	public void run()
	{
	  this.receiveData();
	  
	}

	private String getComputerName()
	{
	    Map<String, String> env = System.getenv();
	    if (env.containsKey("COMPUTERNAME"))
	        return env.get("COMPUTERNAME");
	    else if (env.containsKey("HOSTNAME"))
	        return env.get("HOSTNAME");
	    else
	        return "Unknown Computer";
	}
	
	private void receiveData()
	{
		try
		{
			applyProperties();
			setRunningState(RunningState.STARTED);
			OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
      String hostname = getComputerName();
      InetAddress addr;
      String address = "127.0.0.1";
      try
      {
          DatagramSocket socket = new DatagramSocket();
          socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
          address = socket.getLocalAddress().getHostAddress();
          hostname = socket.getLocalAddress().getCanonicalHostName();
      }
      catch (UnknownHostException ex)
      {
          log.error("Hostname can not be resolved. " + ex.getMessage());
      }
			while(isRunning())
			{
	      String json = "{\"OperatingSystemInformation\" : {";
        json += "\"hostname\": ";
        json += "\"" + hostname + "\",";
        json += "\"ip\": ";
        json += "\"" + address + "\",";
	      
	      for (Method method : operatingSystemMXBean.getClass().getDeclaredMethods())
	      {
	        method.setAccessible(true);
	        if (method.getName().startsWith("get") && Modifier.isPublic(method.getModifiers())) 
	        {
	            Object value;
	            try {
	                json += "\"" + method.getName().substring(3) + "\": ";
	                value = method.invoke(operatingSystemMXBean);
	                json += value;
	                json += ",";
	            } 
	            catch (Exception e) 
	            {
	                value = e;
	            } // try
              log.debug(method.getName().substring(3) + ":" + value);

	        } // if
	      } // for
	      int nbThreads = Thread.getAllStackTraces().keySet().size();
	      json += "\"threads\":" + nbThreads + ",";
	      json += "\"DatetimeStamp\":" + new Date().getTime();
        json += "}}";              
        log.debug(json);
	      try
	      {
	        receive(json.getBytes());
	      }
	      catch (RuntimeException e)
	      {
	        e.printStackTrace();
	      }
	      Thread.sleep((long)(updateIntervalSeconds * 1000));
			}
		}
		catch (Throwable ex)
		{
			log.error("UNEXPECTED_ERROR", ex);
			setRunningState(RunningState.ERROR);
		}
	}

	private void receive(byte[] bytes)
	{
		if (bytes != null && bytes.length > 0)
		{
			String str = new String(bytes);
			str = str + '\n';
			byte[] newBytes = str.getBytes();

			ByteBuffer bb = ByteBuffer.allocate(newBytes.length);
			try
			{
				bb.put(newBytes);
				bb.flip();
				byteListener.receive(bb, "");
				bb.clear();
			}
			catch (BufferOverflowException boe)
			{
				log.error("BUFFER_OVERFLOW_ERROR", boe);
				bb.clear();
				setRunningState(RunningState.ERROR);
			}
			catch (Exception e)
			{
				log.error("UNEXPECTED_ERROR2", e);
				stop();
				setRunningState(RunningState.ERROR);
			}
		}
	}

	private void applyProperties() throws Exception
	{
		if (getProperty("updateIntervalSeconds").isValid())
		{
			double value = Converter.convertToDouble(getProperty("updateIntervalSeconds").getValue());
			if (value > 0 && value != updateIntervalSeconds)
			{
				updateIntervalSeconds = value;
			}
		}
	}

	public synchronized void stop()
	{
		setRunningState(RunningState.STOPPED);
	}

	@Override
	public boolean isClusterable()
	{
		return false;
	}
}
