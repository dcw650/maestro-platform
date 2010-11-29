/*
  Copyright (C) 2010 Zheng Cai

  Maestro is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Maestro is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Maestro.  If not, see <http://www.gnu.org/licenses/>.
*/

package apps.openflow;

import events.openflow.*;
import drivers.OFPConstants;
import sys.Parameters;
import views.ViewsIOBucket;
import views.openflow.*;
import apps.App;

/**
 * The RouteFlowApp tries to set up a path for the FlowIns generated by
 * the LocationManagementApp, based on the all-pair shortest-path routing
 * table. It also sends back the packet if the packet is not buffered.
 */
public class RouteFlowApp extends App {
    private long currentCookie = 1;

    synchronized private long nextCookie() {
	return currentCookie ++;
    }

    @Override
	public ViewsIOBucket process(ViewsIOBucket input) {
	JoinedSwitchesView sws = (JoinedSwitchesView)input.getView(0);
	RoutingIntraView rt = (RoutingIntraView)input.getView(1);
	FlowsInView fis = (FlowsInView)input.getView(2);

	FlowConfigView config = new FlowConfigView();
	PacketsOutView pkts = new PacketsOutView();
	for (FlowsInView.FlowIn fl : fis.queue) {
	    //. This is a broadcast packet, send it out to OFPP_FLOOD
	    if (fl.dst == RegisteredHostsView.MAC_Broad_Cast) {
		config.addFlowModEvent(createFlowModAdd(fl.pi, fl.pi.dpid, OFPConstants.OfpPort.OFPP_FLOOD));
		addPacketOut(fl.pi, OFPConstants.OfpPort.OFPP_FLOOD, pkts);
	    } else if (fl.dst == RegisteredHostsView.Location_Unknown) {
		addPacketOut(fl.pi, OFPConstants.OfpPort.OFPP_FLOOD, pkts);
	    } else { //. Regular packet
		long from = fl.pi.dpid;
		long to = fl.dst.dpid;
		RoutingIntraView.Route rtv = rt.getNextHop(from, to);
		long current = from;
		if (rtv == null) {
		    //. Right now we are at the destination switch
		    //. Otherwise it is still in transient state
		    if (from == to) {
			//. Make sure the inport and outport are different
			if (fl.pi.inPort != fl.dst.port) {
			    //. Add the flow entry
			    config.addFlowModEvent(createFlowModAdd(fl.pi, from, fl.dst.port));
			    addPacketOut(fl.pi, fl.dst.port, pkts);
			}
		    }
		    continue;
		}
			
		addPacketOut(fl.pi, rtv.port, pkts);
			
		long next = rtv.next;
		while (current != to) {
		    config.addFlowModEvent(createFlowModAdd(fl.pi, current, rtv.port));
		    rtv = rt.getNextHop(next, to);
		    //. Still in transient state
		    if (rtv == null) {
			break;
		    }
		    current = next;
		    next = rtv.next;
		}

		if (Parameters.useMemoryMgnt) {
		    Parameters.am.memMgr.freePacketInEvent(fl.pi);
		}
	    }
	}
		
	ViewsIOBucket output = new ViewsIOBucket();
	output.addView(0, config);
	output.addView(1, pkts);
	return output;
    }

    private FlowModEvent createFlowModAdd(PacketInEvent pi, long dpid, int port) {
	FlowModEvent fm = null;
	if (Parameters.useMemoryMgnt) {
	    fm = Parameters.am.memMgr.allocFlowModEvent();
	} else {
	    fm = new FlowModEvent();
	}
		
	fm.xid = pi.xid;
	fm.flow = pi.flow;
	fm.dpid = dpid;
	fm.inPort = pi.inPort;
	fm.cookie = nextCookie();
	fm.outPort = port;
	fm.bufferId = pi.bufferId;
	fm.command = OFPConstants.OfpFlowModCommand.OFPFC_ADD;
	fm.idleTimeout = 30;
	fm.hardTimeout = 180;
	fm.priority = 100;
	fm.flags = 0;
	fm.reserved = 0;
		
	if (Parameters.useMemoryMgnt) {
	    PacketOutEvent.setOutputAction(port, fm.actions[0]);
	} else {
	    fm.actions = new PacketOutEvent.Action[1];
	    fm.actions[0] = PacketOutEvent.makeOutputAction(port);
	}
		
	fm.actionsLen = fm.actions[0].len;
	return fm;
    }

    private void addPacketOut(PacketInEvent pi, int port, PacketsOutView pkts) {
	PacketOutEvent po;
	if (Parameters.useMemoryMgnt) {
	    po = Parameters.am.memMgr.allocPacketOutEvent();
	} else {
	    po = new PacketOutEvent();
	}
	po.xid = pi.xid;
	po.dpid = pi.dpid;
	po.bufferId = pi.bufferId;
	po.inPort = pi.inPort;
	po.dataLen = pi.totalLen;
	po.data = pi.data;

	if (Parameters.useMemoryMgnt) {
	    PacketOutEvent.setOutputAction(port, po.actions[0]);
	} else {
	    po.actions = new PacketOutEvent.Action[1];
	    po.actions[0] = PacketOutEvent.makeOutputAction(port);
	}
	po.actionsLen = po.actions[0].len;
	pkts.addPacketOutEvent(po);
    }
}