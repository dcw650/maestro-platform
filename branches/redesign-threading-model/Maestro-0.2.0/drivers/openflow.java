/*
  openflow.java

  Copyright (C) 2010  Rice University

  This software is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This software is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public
  License along with this software; if not, write to the Free Software
  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
*/

package drivers;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

import events.Event;
import events.openflow.FlowModEvent;
import events.openflow.LLDPPacketInEvent;
import events.openflow.PacketInEvent;
import events.openflow.PacketOutEvent;
import events.openflow.SwitchJoinEvent;
import events.openflow.ToSpecificSwitchEvent;
import sys.Constants;
import sys.Parameters;
import sys.Utilities;
import sys.DataLogManager;
import drivers.OFPConstants;
import headers.EthernetHeader;
import headers.LLDPHeader;

/**
 * The driver for OpenFlow switches
 * @author Zheng Cai
 */
public class openflow extends Driver {
    Random random;
    public boolean toprint = true;
	
    private static class Switch {
	public long dpid = 0;
	public SocketChannel channel = null;
	public int bufferSize = 0;
	public byte[] buffer = new byte[1024];
	//public ByteBuffer outputBuffer;

	public boolean chopping = false;
	public boolean sending = false;

	public int chances = 0;
	public int skipped = 0;
	public int totalSize = 0;
	public int zeroes = 0;

	public long totalProcessed = 0;
		
	/** For those lldps received before the dpid of this switch is known */
	private LinkedList<LLDPPacketInEvent> lldpQueue;
		
	public Switch() {
	    lldpQueue = new LinkedList<LLDPPacketInEvent>();
	    //outputBuffer = ByteBuffer.allocate(20000);
	}

	public int send(ByteBuffer pkt) {
	    int ret = 0;
	    sending = true;
	    synchronized(channel) {
		try {
		    int count = 0;
		    while(pkt.hasRemaining()) {
			long before = 0;

			int wrt = channel.write(pkt);

			/*
			if (wrt == 0) {
			    Selector selector = Selector.open();
			    channel.register(selector, SelectionKey.OP_WRITE);
			    boolean flag = true;
			    while (flag) {
				selector.select();
				for (SelectionKey key : selector.selectedKeys()) {
				    if (key.isValid() && key.isWritable()) {
					flag = false;
					break;
				    }
				}
			    }
			}
			*/			
			count++;
			if (count > 300000) {
			    System.err.println("Too many tries for "+dpid);
			    sending = false;
			    return ret;
			}
			
		    }
		} catch (Exception e) {
		    //e.printStackTrace();
		}
	    }
	    sending = false;
	    return ret;
	}
    }

    /**
     * Switch socket round-robin pool implementation
     */
    private static class SwitchRRPool {
	private openflow of;
	private ArrayList<Switch> pool = null;
	private int currentPos = 0;

	public SwitchRRPool(openflow o) {
	    pool = new ArrayList<Switch>();
	    of = o;
	}

	public void addSwitch(Switch sw) {
	    synchronized(pool) {
		pool.add(sw);
	    }
	}

	public void removeSwitch(Switch sw) {
	    synchronized(pool) {
		int idx = pool.indexOf(sw);
		if (-1 != idx)
		    pool.remove(idx);
	    }
	}

	public Switch getSwitchAt(int idx) {
	    if (idx >= pool.size())
		return null;
	    return pool.get(idx);
	}

	public Switch nextSwitch() {
	    synchronized(pool) {
		int size = pool.size();
		if (0 == size) {
		    return null;
		}
		for (int i = 0; i < size; i++) {
		    Switch sw = null;
		    try {
			sw = pool.get(currentPos);
		    } catch (IndexOutOfBoundsException e) {
			//. TODO: write logs in memory to disk here
			Parameters.am.dataLogMgr.dumpLogs();
			//of.print();
			//System.err.println("System existing...");
			Utilities.ForceExit(0);
		    }
		    currentPos = (currentPos+1)%size;
		    if (!sw.chopping) {
			sw.chopping = true;
			return sw;
		    }
		}

		//. All busy chopping
		return null;
	    }
	}

	public int getSize() {
	    return pool.size();
	}
    }
	
    private final static int BUFFERSIZE = 1024;
	
    private HashMap<Long, Switch> dpid2switch;
    private HashMap<SocketChannel, Switch> chnl2switch;
    private Selector s;
    private SwitchRRPool swRRPool;
	
    public openflow() {
    	random = new Random();
    	dpid2switch = new HashMap<Long, Switch>();
    	chnl2switch = new HashMap<SocketChannel, Switch>();
	swRRPool = new SwitchRRPool(this);
    }
    
    public int SendPktOut(long dpid, ByteBuffer pkt) {
	Switch target;
	target = dpid2switch.get(dpid);
	Utilities.Assert(target != null, "Cannot find target switch for dpid "+dpid);
	return target.send(pkt);
    }

    public SwitchRRPool getRRPool() {
	return swRRPool;
    }
    
    public void start() {
    	try {
	    int port = Parameters.listenPort;
	    s = Selector.open();
	    ServerSocketChannel acceptChannel = ServerSocketChannel.open();
	    acceptChannel.configureBlocking(false);
	    byte[] ip = {0, 0, 0, 0};
	    InetAddress lh = InetAddress.getByAddress(ip);
	    InetSocketAddress isa = new InetSocketAddress(lh, port);
	    acceptChannel.socket().bind(isa);
	    acceptChannel.socket().setReuseAddress(true);
			
	    SelectionKey acceptKey = acceptChannel.register(s, SelectionKey.OP_ACCEPT);
	    while (s.select() > 0) {
		Set<SelectionKey> readyKeys = s.selectedKeys();
		for (SelectionKey k : readyKeys) {
		    try {
			if (k.isAcceptable()) {
			    SocketChannel channel = ((ServerSocketChannel)k.channel()).accept();
			    channel.configureBlocking(false);
			    //SelectionKey clientKey = channel.register(s, SelectionKey.OP_READ);
			    Switch sw = new Switch();
			    sw.channel = channel;
			    chnl2switch.put(channel, sw);
			    swRRPool.addSwitch(sw);
			    sendHelloMessage(sw);
			}
		    } catch (IOException e) {
			e.printStackTrace();
			k.channel().close();
			System.exit(0);
		    }
		}
		readyKeys.clear();
	    }
	} catch (IOException e) {
	    System.err.println("IOException in Selector.open()");
	    e.printStackTrace();
	}
    }

    private static class RawMessage {
	public byte[] buf;
	public int start;
	public int length;

	public RawMessage(byte[] b, int s, int l) {
	    buf = b;
	    start = s;
	    length = l;
	}
    }

    /** Chop raw messages from a buffer, leaving half-done bytes in the switch's buffer */
    public ArrayList<RawMessage> chopMessages(Switch sw, ByteBuffer buffer, int size) {
	byte[] buf = buffer.array();
	ArrayList<RawMessage> ret = new ArrayList<RawMessage>();
	int bufPos = 0;
	if (sw.bufferSize >= OFPConstants.OfpConstants.OFP_HEADER_LEN) {
	    int length = Utilities.getNetworkBytesUint16(sw.buffer, 2);
	    
	    if ((length - sw.bufferSize) <= size) {
		byte[] b = new byte[length];
		Utilities.memcpy(b, 0, sw.buffer, 0, sw.bufferSize);
		Utilities.memcpy(b, sw.bufferSize, buf, bufPos, length-sw.bufferSize);                 
		size -= (length-sw.bufferSize);                                              
		bufPos += (length-sw.bufferSize);                                               
		ret.add(new RawMessage(b, 0, length));
		sw.bufferSize = 0;                                                           
	    } else {                                                                        
		Utilities.memcpy(sw.buffer, sw.bufferSize, buf, bufPos, size);                                  
		sw.bufferSize += size;
		return ret;
	    }                                                                               
	} else if (sw.bufferSize > 0) {                                                  
	    if ((sw.bufferSize + size) >= OFPConstants.OfpConstants.OFP_HEADER_LEN) {                     
		Utilities.memcpy(sw.buffer, sw.bufferSize, buf, bufPos,
				 OFPConstants.OfpConstants.OFP_HEADER_LEN-sw.bufferSize);                             
		size -= (OFPConstants.OfpConstants.OFP_HEADER_LEN-sw.bufferSize);                           
		bufPos += (OFPConstants.OfpConstants.OFP_HEADER_LEN-sw.bufferSize);                            
		sw.bufferSize = OFPConstants.OfpConstants.OFP_HEADER_LEN;                                   
    		
		int length = Utilities.getNetworkBytesUint16(sw.buffer, 2);
    		
		if ((length - sw.bufferSize) <= size) {
		    byte[] b = new byte[length];
		    Utilities.memcpy(b, 0, sw.buffer, 0, sw.bufferSize);
		    Utilities.memcpy(b, sw.bufferSize, buf, bufPos, length-sw.bufferSize);                 
		    size -= (length-sw.bufferSize);                                              
		    bufPos += (length-sw.bufferSize);                                               
		    ret.add(new RawMessage(b, 0, length));
		    sw.bufferSize = 0;
		} else {                                                                      
		    Utilities.memcpy(sw.buffer, sw.bufferSize, buf, bufPos, size);                                
		    sw.bufferSize += size;
		    return ret;                                                                     
		}                                                                             
	    } else {                                                                        
		//. Still not enough for holding ofp_header                                    
		Utilities.memcpy(sw.buffer, sw.bufferSize, buf, bufPos, size);                                  
		sw.bufferSize += size;
		return ret;
	    }                                                                               
	}
    	
	while (size > 0) {    		                                                                                    
	    //. Not enough for holding ofp_header                                            
	    if (size < OFPConstants.OfpConstants.OFP_HEADER_LEN) {                                         
		Utilities.memcpy(sw.buffer, 0, buf, bufPos, size);                                                 
		sw.bufferSize = size;                                                        
		break;                                                                        
	    } 
	    int length = Utilities.getNetworkBytesUint16(buf, bufPos+2);
	    if (length > size) {                                                     
		Utilities.memcpy(sw.buffer, 0, buf, bufPos, size);                                                 
		sw.bufferSize = size;                                                        
		break;                                                                        
	    }

	    if (0 == length) {
		Utilities.printlnDebug("ERROR: length in OFP header is 0!");
		return ret;
	    }
		
	    //. buffer is not going to be shared among worker threads, so no need to copy
	    ret.add(new RawMessage(buf, bufPos, length));
	    size -= length;                                                                 
	    bufPos += length;                                                                  
	}
	return ret;
    }

    /**
      @return whether this packet is a PacketIn
    */
    public boolean dispatchPacket(Switch sw, byte[] buffer, int pos, int size, boolean flush) {
    	
    	short type = Utilities.getNetworkBytesUint8(buffer, pos+1);
	int length = Utilities.getNetworkBytesUint16(buffer, pos+2);
	switch(type) {
	case OFPConstants.PacketTypes.OFPT_HELLO:
	    //Utilities.printlnDebug("Got hello");
	    sendFeatureRequest(sw);
	    break;
	case OFPConstants.PacketTypes.OFPT_ECHO_REQUEST:
	    //Utilities.printlnDebug("Got echo");
	    Utilities.setNetworkBytesUint8(buffer, pos+1, OFPConstants.PacketTypes.OFPT_ECHO_REPLY);	    
	    ByteBuffer buf = ByteBuffer.allocate(length);
	    if (size != length) {
		Utilities.printlnDebug("BAD! In handling echo_request: size != length");
	    } else {
		buf.put(buffer, pos, length);
	    }
	    sw.send(buf);
	    break;
	case OFPConstants.PacketTypes.OFPT_FEATURES_REPLY:
	    //Utilities.printlnDebug("Got features_reply");
	    handleFeaturesReply(sw, buffer, pos, length);
	    break;
	case OFPConstants.PacketTypes.OFPT_PACKET_IN:
	    return handlePacketIn(sw, buffer, pos, length, flush);
	    //break;
	default:
	    break;
	}
	return false;
    }
    
    public void handleFeaturesReply(Switch sw, byte[] buffer, int pos, int length) {
    	pos += OFPConstants.OfpConstants.OFP_HEADER_LEN;
    	SwitchJoinEvent sj = new SwitchJoinEvent();
    	sj.dpid = Utilities.getNetworkBytesUint64(buffer, pos);
    	pos += 8;
    	sw.dpid = sj.dpid;
    	synchronized(dpid2switch) {
	    dpid2switch.put(sw.dpid, sw);
	}
    	
    	sj.nBuffers = Utilities.getNetworkBytesUint32(buffer, pos);
    	pos += 4;
    	sj.nTables = Utilities.getNetworkBytesUint8(buffer, pos);
    	pos += 4;
    	sj.capabilities = Utilities.getNetworkBytesUint32(buffer, pos);
    	pos += 4;
    	sj.actions = Utilities.getNetworkBytesUint32(buffer, pos);
    	pos += 4;
    	sj.nPorts = (length-OFPConstants.OfpConstants.OFP_SWITCH_FEATURES_LEN)
	    /OFPConstants.OfpConstants.OFP_PHY_PORT_LEN;
    	sj.ports = new SwitchJoinEvent.PhysicalPort[sj.nPorts];
    	for (int i=0;i<sj.nPorts;i++) {
	    sj.ports[i] = new SwitchJoinEvent.PhysicalPort();
	    SwitchJoinEvent.PhysicalPort p = sj.ports[i];
	    p.portNo = Utilities.getNetworkBytesUint16(buffer, pos);
	    pos += 2;
	    for (int j=0;j<OFPConstants.OfpConstants.OFP_ETH_ALEN;j++) {
		p.hwAddr[j] = Utilities.getNetworkBytesUint8(buffer, pos++);
	    }
	    for (int j=0;j<OFPConstants.OfpConstants.OFP_MAX_PORT_NAME_LEN;j++) {
		p.name[j] = buffer[pos++];
	    }
	    p.config = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
	    p.state = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
	    p.curr = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
	    p.advertised = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
	    p.supported = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
	    p.peer = Utilities.getNetworkBytesUint32(buffer, pos);
	    pos += 4;
    	}
    	vm.postEvent(sj);
    	synchronized(sw.lldpQueue) {
	    int size = sw.lldpQueue.size();
	    //LLDPPacketInEvent last = null;
	    for (LLDPPacketInEvent lldp : sw.lldpQueue) {
		lldp.dstDpid = sw.dpid;
		if (size > 1)
		    vm.postEventWithoutTrigger(lldp);
		else
		    vm.postEvent(lldp);
		size --;
	    }
	    sw.lldpQueue.clear();
    	}
    }

    public boolean handlePacketIn(Switch sw, byte[] buffer, int pos, int length, boolean flush) {
	PacketInEvent pi;
	if (Parameters.useMemoryMgnt) {
	    pi = Parameters.am.memMgr.allocPacketInEvent();
	} else {
	    pi = new PacketInEvent();
	}
	pi.flush = flush;
    	
    	pi.xid = Utilities.getNetworkBytesUint32(buffer, pos+4);
    	pos += OFPConstants.OfpConstants.OFP_HEADER_LEN;
    	pi.dpid = sw.dpid;
    	pi.bufferId = Utilities.getNetworkBytesUint32(buffer, pos);
    	pos += 4;
    	pi.totalLen = Utilities.getNetworkBytesUint16(buffer, pos);
    	pos += 2;
    	pi.inPort = Utilities.getNetworkBytesUint16(buffer, pos);
    	pos += 2;
    	pi.reason = Utilities.getNetworkBytesUint8(buffer, pos);
    	pos += 2; //. including 1 byte pad
    	
	/*
    	Utilities.Assert(pi.totalLen == (length-OFPConstants.OfpConstants.OFP_PACKET_IN_LEN), 
			 String.format("unmatched PacketIn data length: totalLen=%d bufLen=%d", 
				       pi.totalLen, (length-OFPConstants.OfpConstants.OFP_PACKET_IN_LEN)));
	
	
    	if (Parameters.useMemoryMgnt) {
	    pi.data = Parameters.am.memMgr.allocPacketInEventDataPayload(pi.totalLen);
    	}
    	else {
	    pi.data = new PacketInEvent.DataPayload(pi.totalLen);
    	}
	
    	Utilities.memcpy(pi.data.data, 0, buffer, pos, pi.totalLen);
	*/

	///////////////////// WARNING: CURRENT A HACK HERE, IGNORING pi.totalLen
	pi.totalLen = length-OFPConstants.OfpConstants.OFP_PACKET_IN_LEN;
	if (Parameters.useMemoryMgnt) {
	    pi.data = Parameters.am.memMgr.allocPacketInEventDataPayload(pi.totalLen);
	}
	else {
	    pi.data = new PacketInEvent.DataPayload(pi.totalLen);
	}

	Utilities.memcpy(pi.data.data, 0, buffer, pos, pi.totalLen);
	////////////////////////////////

	//. Currently assume that all packets are ethernet frames
	EthernetHeader eth;
	if (Parameters.useMemoryMgnt) {
	    eth = Parameters.am.memMgr.allocEthernetHeader();
	} else {
	    eth = new EthernetHeader();
	}
	pos = eth.parseHeader(buffer, pos);
	pi.extractFlowInfo(eth);

	if (OFPConstants.OfpConstants.ETH_TYPE_LLDP == pi.flow.dlType) {
	    LLDPPacketInEvent lldp = new LLDPPacketInEvent();
	    if(!(eth.inner instanceof LLDPHeader)) {
		Utilities.printlnDebug("The LLDP packet is not correctly formated");
		return false;
	    }
	    LLDPHeader lldpHeader = (LLDPHeader)eth.inner;
	    lldp.srcDpid = Utilities.getNetworkBytesUint64(lldpHeader.chassisId.value, 0);
	    lldp.srcPort = Utilities.getNetworkBytesUint16(lldpHeader.portId.value, 0);
	    lldp.ttl = Utilities.getNetworkBytesUint16(lldpHeader.ttl.value, 0);
	    
	    lldp.dstDpid =pi.dpid;
	    lldp.dstPort = pi.inPort;
	    if (Parameters.useMemoryMgnt) {
		Parameters.am.memMgr.freePacketInEvent(pi);
		eth.free();
	    }
	    if (sw.dpid == 0) {
		synchronized(sw.lldpQueue) {
		    sw.lldpQueue.addLast(lldp);
		}
	    } else {
		vm.postEvent(lldp);
	    }
	    return false;
	} else {
	    eth.free();
	    if (Parameters.divide > 0) {
		int toWhich = Parameters.am.workerMgr.getCurrentWorkerID();
		vm.postEventConcurrent(pi, toWhich);
	    } else {
		vm.postEvent(pi);
	    }
	    return true;
	}
    }

    /**
     * The worker thread is free, flush all the batched PacketsInEvent to be 
     * processed immediately
     */
    public void flush() {
	PacketInEvent pi = PacketInEvent.flushDummy;
	pi.dummy = true;
	if (Parameters.divide > 0) {
	    int toWhich = Parameters.am.workerMgr.getCurrentWorkerID();
	    vm.postEventConcurrent(pi, toWhich);
	} else {
	    vm.postEvent(pi);
	}
    }
    
    public void sendHelloMessage(Switch sw) {
    	ByteBuffer buffer = ByteBuffer.allocate(OFPConstants.OfpConstants.OFP_HEADER_LEN);
    	byte[] packet = buffer.array();
    	int pos = 0;
    	Utilities.setNetworkBytesUint8(packet, pos++, OFPConstants.OfpConstants.OFP_VERSION);
    	Utilities.setNetworkBytesUint8(packet, pos++, OFPConstants.PacketTypes.OFPT_HELLO);
    	Utilities.setNetworkBytesUint16(packet, pos, OFPConstants.OfpConstants.OFP_HEADER_LEN);
    	pos += 2;
    	Utilities.setNetworkBytesUint32(packet, pos, 0);
    	
	sw.send(buffer);
    }
    
    public void sendFeatureRequest(Switch sw) {
    	ByteBuffer buffer = ByteBuffer.allocate(OFPConstants.OfpConstants.OFP_HEADER_LEN);
    	byte[] packet = buffer.array();
    	int pos = 0;
    	Utilities.setNetworkBytesUint8(packet, pos++, OFPConstants.OfpConstants.OFP_VERSION);
    	Utilities.setNetworkBytesUint8(packet, pos++, OFPConstants.PacketTypes.OFPT_FEATURES_REQUEST);
    	Utilities.setNetworkBytesUint16(packet, pos, OFPConstants.OfpConstants.OFP_HEADER_LEN);
    	pos += 2;
    	Utilities.setNetworkBytesUint32(packet, pos, 0);
    	
	sw.send(buffer);
    }

    class LogContent extends DataLogManager.Content {
	public long dpid;
	public int size;
	public LogContent(long d, int s) {
	    dpid = d;
	    size = s;
	}
	public String toString() {
	    return String.format("%d %d\n", dpid, size);
	}
    }
    
    @Override
	public boolean commitEvent(ArrayList<Event> events) {
	if (events.size() == 0) {
	    return true;
	}
	Event e = events.get(0);
	if (e == null) {
	    return false;
	}
	if (e instanceof PacketOutEvent) {
	    int size = events.size();
	    boolean ret = true;
	    //if (((PacketOutEvent)e).send)
	    ret = processToSpecificSwitchEvent(events);

	    if (ret) {
		Parameters.am.workerMgr.increaseCounter(size);
		
		if (Parameters.am.dataLogMgr.enabled) {
		    Parameters.am.dataLogMgr.addEntry(new LogContent(((PacketOutEvent)e).dpid, size));
		}
	    }
	    
	    return ret;
	}
	if (e instanceof FlowModEvent) {
	    return processToSpecificSwitchEvent(events);
	    //return true;
	}
	return true;
    }

    class Partition {
	public ArrayList<Event> es;// = new ArrayList<Event>();
	public int totalLength = 0;
	public long dpid;
	public Switch sw;
		
	public ByteBuffer toPacket() {
	    ByteBuffer pkt;
	    if (Parameters.useMemoryMgnt) {
		pkt = Parameters.am.memMgr.allocByteBuffer(totalLength);
	    }
	    else {
		pkt = ByteBuffer.allocate(totalLength);
	    }
	    
	    int pos = 0;
	    for (Event e : es) {
		ToSpecificSwitchEvent tsse = (ToSpecificSwitchEvent)e;
		Utilities.Assert(dpid == tsse.dpid, "dpid does not match!");
		pos += tsse.convertToBytes(pkt.array(), pos);
		if (Parameters.useMemoryMgnt) {
		    if (tsse instanceof FlowModEvent) {
			Parameters.am.memMgr.freeFlowModEvent((FlowModEvent)tsse);
		    }
		    if (tsse instanceof PacketOutEvent) {
			Parameters.am.memMgr.freePacketInEventDataPayload(((PacketOutEvent)tsse).data);
			Parameters.am.memMgr.freePacketOutEvent((PacketOutEvent)tsse);
		    }
		}
	    }
	    pkt.limit(totalLength);

	    return pkt;
    	}
    }
	
    /* For better paralizability of memory management,
     * we do not do partition consolidation
     */
    //HashMap<Long, Partition> pendingPts = new HashMap<Long, Partition>();
    
	
    private boolean processToSpecificSwitchEvent(ArrayList<Event> events) {
	// Assume that the dpids in "events" are the same
	long dpid = ((ToSpecificSwitchEvent)events.get(0)).dpid;
	Switch target = dpid2switch.get(dpid);
	if (null == target)
	    return false;
	if (target.sending)
	    return false;
	
	Partition pt = new Partition();
	pt.dpid = dpid;
	pt.sw = target;
	for (Event e : events) {
	    ToSpecificSwitchEvent tsse = (ToSpecificSwitchEvent)e;
	    pt.totalLength += tsse.getLength();
	}
	pt.es = events;

	/*
	if (Parameters.divide == 0) {
	    if (Parameters.batchOutput) {
		ByteBuffer pkt = pt.toPacket();
		SendPktOut(pt.dpid, pkt, pkt.array().length);
	    } else {
		for (Event e : pt.es) {
		    ToSpecificSwitchEvent tsse = (ToSpecificSwitchEvent)e;
		    ByteBuffer pkt = ByteBuffer.allocate(tsse.getLength());
		    tsse.convertToBytes(pkt.array(), 0);
		    SendPktOut(tsse.dpid, pkt, pkt.array().length);
		}
	    }
	    return true;
	}
	*/
		
	boolean toRun = true;
	
	/* For better paralizability of memory management,
	 * we do not do partition consolidation
	 */
	/*
	synchronized(pendingPts) {
	    Partition pp = pendingPts.get(pt.dpid);
	    if (pp != null) {
		pp.es.addAll(pt.es);
		pp.totalLength += pt.totalLength;
		//. We can consolidate this partition to an existing one
		toRun = false;
		pt.es.clear();
	    }
	}
	*/

	if (toRun) {
	    /* For better paralizability of memory management,
	     * we do not do partition consolidation
	     */
	    /*
	    synchronized(pendingPts) {
		pendingPts.put(pt.dpid, pt);
	    }
	    */


	    /* For better paralizability of memory management,
	     * we do not do partition consolidation
	     */
	    /*
	    // To avoid deadlock
	    synchronized(of.pendingPts) {
	    of.pendingPts.remove(pt.dpid);
	    }
	    */
	    if ( pt.es.get(0) instanceof PacketOutEvent)
		target.totalProcessed += pt.es.size();
	    
	    if (Parameters.batchOutput) {
		ByteBuffer pkt = pt.toPacket();
		SendPktOut(pt.dpid, pkt);
		if (Parameters.useMemoryMgnt) {
		    Parameters.am.memMgr.freeByteBuffer(pkt);
		}
	    } else {
		for (Event e : pt.es) {
		    ToSpecificSwitchEvent tsse = (ToSpecificSwitchEvent)e;
		    ByteBuffer pkt = ByteBuffer.allocate(tsse.getLength());
		    tsse.convertToBytes(pkt.array(), 0);
		    SendPktOut(tsse.dpid, pkt);
		    if (Parameters.useMemoryMgnt) {
			if (tsse instanceof FlowModEvent) {
			    Parameters.am.memMgr.freeFlowModEvent((FlowModEvent)tsse);
			}
			if (tsse instanceof PacketOutEvent) {
			    Parameters.am.memMgr.freePacketInEventDataPayload(((PacketOutEvent)tsse).data);
			    Parameters.am.memMgr.freePacketOutEvent((PacketOutEvent)tsse);
			}
		    }
		}
	    }
	    
	    pt.es.clear();
	}

	return true;
    }
	
    public void print() {
	for (long i=1;i<=60;i++) {
	    Switch sw = dpid2switch.get(i);
	    if (sw.totalSize > 0)
		System.err.println("Switch # "+i+" chances "+sw.chances+" processed "+sw.totalProcessed+" totalSize "+sw.totalSize+" zeroes "+sw.zeroes);
	}
    }

    public static class OpenFlowTask implements Runnable {
	private openflow of;

	public OpenFlowTask(openflow o) {
	    of = o;
	}

	public void run() {
	    int workerID = Parameters.am.workerMgr.getCurrentWorkerID();
	    ByteBuffer buffer = ByteBuffer.allocate(BUFFERSIZE);
	    //ByteBuffer buffer = ByteBuffer.allocate(78*30);
	    int voidRead = 0;
	    int idx = 0;
	    int trySkipped = 0;
	    final int HOWMANYTRIES = 40;
	    int ibt = Parameters.batchInputNum;
	    int maxIbt = Parameters.batchInputNum;
	    final int step = 10;
	    boolean congested = false;
	    boolean increasing = true;
	    int batched = 0;
	    long begin = 0, finish = 0;
	    double lastScore = 0;
	    int printFreq = 0;
	    int count = 0;
	    final int MaxSteps = 1000; //. Maximum IBT is MaxSteps*step
	    final int HistoryWeight = 80; //. History value weighs 80%
	    final long MaxDelay = 1000; //. MicroSecond
	    double[] history = new double[MaxSteps];
	    long lastRound = System.nanoTime();

	    LinkedList<Switch> skipped = new LinkedList<Switch>();
	    while (true) {
		/*
		Switch sw = of.getRRPool().nextSwitch();
		if (null == sw) {
		    continue;
		}
		*/

		Switch sw = null;
		if (idx < of.getRRPool().getSize()) {
		    sw = of.getRRPool().getSwitchAt(idx++);
		}
		if (null == sw) {
		    if (skipped.size() == 0 || trySkipped >= HOWMANYTRIES) {
			skipped.clear();
			idx = 0;
			trySkipped = 0;
			/*
			long now = System.nanoTime();
			if (workerID == 1) {
			    System.err.println((now-lastRound)/1000);
			}
			lastRound = now;
			*/
			continue;
		    } else {
			sw = skipped.removeFirst();
			trySkipped ++;
		    }
		}

		synchronized (sw) {
		    if (sw.chopping) {
			skipped.addLast(sw);
			continue;
		    }
		    else
			sw.chopping = true;
		}
		
		long before = 0;
		if (Parameters.warmuped) {
		    sw.chances ++;
		    //before = System.nanoTime();
		}
		
		try {
		    buffer.clear();
		    int size = sw.channel.read(buffer);
		    if (size == -1) {
			sw.chopping = false;
			handleLeftSwitch(sw);
			continue;
		    } else if (size == 0) {
			if (Parameters.warmuped)
			    sw.zeroes ++;
			sw.chopping = false;
			// Whether flush the batch if there is no pending requests left
			voidRead ++;
			if (voidRead > of.getRRPool().getSize()) {
			    of.flush();
			    voidRead = 0;
			    batched = 0;
			    increasing = false;
			    congested = false;
			}
			continue;
		    } else if (size == BUFFERSIZE) {
			congested = true;
		    }

		    ArrayList<RawMessage> msgs = of.chopMessages(sw, buffer, size);
		    sw.chopping = false;
		    
		    if (Parameters.warmuped) {
			sw.totalSize += size;
		    }

		    if (of.toprint && sw.dpid == 60 && sw.totalSize >= 80000000) {
			of.print();
			of.toprint = false;
		    }

		    for (RawMessage msg : msgs) {
			if (batched >= ibt) {
			    if (of.dispatchPacket(sw, msg.buf, msg.start, msg.length, true)) {
				long allTime = (System.nanoTime() - begin) / 1000; //. Microsecond
				double score = ((double)batched) / (allTime);
				//double score = ((double)(MaxDelay-allTime)*batched) / (MaxDelay*allTime);
				
				double realScore = score;
				
				//. Adding history into the evaluation
				int hisIdx = ibt / step;
				if (history[hisIdx] == 0) {
				    history[hisIdx] = score;
				} else {
				    score = (score*(100-HistoryWeight) + history[hisIdx]*HistoryWeight) / 100;
				    history[hisIdx] = score;
				}
				
				
				if (workerID == 1) {
				    //System.err.println((count++)+" "+ibt+" "+increasing+" "+score+" ("+realScore+") "+lastScore+" "+allTime);
				    /*
				    System.err.println(Parameters.newCountfm+" "+
						       Parameters.newCountpi+" "+
						       Parameters.newCountpo+" "+
						       Parameters.newCountdata+" "+
						       Parameters.newCountbuffer+" "+
						       Parameters.newCountdr);
				    */
				}

				
				if (allTime > MaxDelay) {
				    increasing = false;
				    if (ibt <= step)
					ibt = step;
				    else
					ibt -= step;
				} else {
				
				    if (score > lastScore) { //. Better
					if (increasing && congested) {
					    ibt += step;
					    if (ibt > maxIbt)
						maxIbt = ibt;
					} else {
					    if (ibt <= step)
						ibt = step;
					    else
						ibt -= step;
					}
				    } else { //. Worse
					increasing = !increasing;
				    }
				}

				//ibt = 1000;
				lastScore = score;
				congested = false;
				
				batched = 0;
			    }
			} else {
			    batched += of.dispatchPacket(sw, msg.buf, msg.start, msg.length, false) ? 1 : 0;
			    if (batched == 1) {
				begin = System.nanoTime();
			    }
			}
		    }
		    
		    voidRead = 0;
		} catch (IOException e) {
		    //e.printStackTrace();
		    //of.print();
		    handleLeftSwitch(sw);
		}
	    }
	}

	public void handleLeftSwitch(Switch sw) {
	    try {
		sw.channel.close();
		//Utilities.printlnDebug("Switch "+sw.dpid+" has left the network");
	    } catch (IOException e) {
		
	    }
	    of.getRRPool().removeSwitch(sw);
	    //. TODO: write logs in memory to disk here
	    Parameters.am.dataLogMgr.dumpLogs();

	    //. TODO: Generate a switch leave event
	}
    }
    
    public Runnable newTask() {
	return new OpenFlowTask(this);
    }

    public void prepareDriverPage(ByteBuffer buffer) {
	buffer.put(String.format("DRIVER\n").getBytes());
	buffer.put(String.format("SystemTime %d\n", System.nanoTime()).getBytes());
	buffer.put(String.format("TotalSwitches %d\n", dpid2switch.size()).getBytes());
	synchronized(dpid2switch) {
	    for (Switch sw : dpid2switch.values()) {
		buffer.put(String.format("Switch %d Processed %d\n", sw.dpid, sw.totalProcessed).getBytes());
	    }
	}
	buffer.flip();
    }
}