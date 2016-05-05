package com.fwumdesoft.shoot.net.server;

import static com.fwumdesoft.shoot.net.NetConstants.HEADER_LENGTH;
import static com.fwumdesoft.shoot.net.NetConstants.HEARTBEAT_TIMEOUT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_CONNECT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_CONNECT_HANDSHAKE;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_DISCONNECT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_HEARTBEAT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_REMOVE_BOLT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_SPAWN_BOLT;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_UPDATE;
import static com.fwumdesoft.shoot.net.NetConstants.MSG_UPDATE_PLAYER;
import static com.fwumdesoft.shoot.net.NetConstants.PACKET_LENGTH;
import static com.fwumdesoft.shoot.net.NetConstants.SERVER_ADDR;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.UUID;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.Pools;
import com.fwumdesoft.shoot.model.Bolt;
import com.fwumdesoft.shoot.model.NetActor;
import com.fwumdesoft.shoot.model.Player;

public class Server extends ApplicationAdapter {
	public static FileHandle logFile;
	
	private Thread heartbeatThread, ioThread, simulationThread;
	private DatagramSocket socket;
	
	private HashMap<UUID, Client> clients;
	
	private Pool<Bolt> boltPool;
	private HashSet<NetActor> netActors;
	
	@Override
	public void create() {
		//setup log file
		logFile = Gdx.files.local("log");
		logFile.delete();
		
		try {
			socket = new DatagramSocket(SERVER_ADDR);
			socket.setReceiveBufferSize(PACKET_LENGTH);
			socket.setSendBufferSize(PACKET_LENGTH);
		} catch(SocketException e) {
			logFile.writeString("Failed to create a DatagramSocket. exiting app...\n", true);
			Gdx.app.exit();
		}
		
		clients = new HashMap<>();
		boltPool = Pools.get(Bolt.class);
		netActors = new HashSet<>();
		
		//handles incoming messages
		ioThread = new Thread(() -> {
			DatagramPacket packet = new DatagramPacket(new byte[PACKET_LENGTH], PACKET_LENGTH);
			ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
			while(!Thread.interrupted()) {
				try {
					packet.setLength(PACKET_LENGTH);
					socket.receive(packet);
					buffer.rewind();
					@SuppressWarnings("unused")
					final int dataLength = buffer.getInt();
					final byte msgId = buffer.get();
					final UUID senderId = new UUID(buffer.getLong(), buffer.getLong());
					final ByteBuffer data = buffer;
					
					switch(msgId)
					{
					case MSG_CONNECT:
						//make sure client doesn't already exist
						if(clients.containsKey(senderId)) {
							logFile.writeString("Client with duplicate ID tried to connect ID: " + senderId + "\n", true);
							break;
						}
						
						Client newClient = new Client(senderId, packet.getSocketAddress());
						clients.put(senderId, newClient);
						synchronized(netActors) {
							netActors.add(new Player(senderId));
						}
						
						//respond to new Client with a MSG_CONNECT_HANDSHAKE
						buffer.rewind();
						buffer.putInt(0);
						buffer.put(MSG_CONNECT_HANDSHAKE);
						buffer.putLong(senderId.getMostSignificantBits());
						buffer.putLong(senderId.getLeastSignificantBits());
						packet.setLength(HEADER_LENGTH);
						newClient.send(socket, packet);
						
						//Tell new client about all netActors in the game right now
						synchronized(netActors) {
							for(NetActor actor : netActors) {
								if(actor instanceof Bolt) {
									Bolt b = (Bolt)actor;
									buffer.rewind();
									buffer.putInt(32); //2 longs 4 floats
									buffer.put(MSG_SPAWN_BOLT);
									buffer.putLong(b.getShooterId().getMostSignificantBits());
									buffer.putLong(b.getShooterId().getLeastSignificantBits());
									buffer.putFloat(b.getX());
									buffer.putFloat(b.getY());
									buffer.putFloat(b.getRotation());
									buffer.putFloat(b.getSpeed());
								} else if(actor instanceof Player) {
									Player p = (Player)actor;
									buffer.rewind();
									buffer.putInt(0);
									buffer.put(MSG_CONNECT);
									buffer.putLong(p.getNetId().getMostSignificantBits());
									buffer.putLong(p.getNetId().getLeastSignificantBits());
									packet.setLength(HEADER_LENGTH);
									newClient.send(socket, packet);
								}
							}
						}
						
						//tell other clients about the new connection
						for(Client c : clients.values()) {
							if(!c.clientId.equals(senderId)) {
								buffer.rewind();
								buffer.putInt(0);
								buffer.put(MSG_CONNECT);
								buffer.putLong(senderId.getMostSignificantBits());
								buffer.putLong(senderId.getLeastSignificantBits());
								packet.setLength(HEADER_LENGTH);
								c.send(socket, packet);
							}
						}
						
						logFile.writeString("Added a new client ID: " + senderId + "\n", true);
						break;
					case MSG_DISCONNECT:
						//check if the client exists
						if(!clients.containsKey(senderId)) {
							logFile.writeString("Client with ID: " + senderId + " tried to disconnect but is already disconnected\n", true);
							break;
						}
						
						//remove Client from clients HashMap
						synchronized(clients) {
							clients.remove(senderId);
						}
						synchronized(netActors) {
							Iterator<NetActor> iter = netActors.iterator();
							while(iter.hasNext()) {
								NetActor actor = iter.next();
								if(actor.getNetId().equals(senderId)) {
									iter.remove();
									break;
								}
							}
						}
						
						//construct message
						buffer.rewind();
						buffer.putInt(0);
						buffer.put(MSG_DISCONNECT);
						buffer.putLong(senderId.getMostSignificantBits());
						buffer.putLong(senderId.getLeastSignificantBits());
						packet.setLength(HEADER_LENGTH);
						
						//send message to all clients
						for(Client c : clients.values()) {
							if(!c.clientId.equals(senderId)) {
								c.send(socket, packet);
							}
						}
						
						logFile.writeString("Disconnected a client ID: " + senderId + "\n", true);
						break;
					case MSG_HEARTBEAT:
						//make sure the client exists
						if(!clients.containsKey(senderId)) {
							logFile.writeString("Client with ID: " + senderId + " tried to send a heartbeat before connecting\n", true);
							break;
						}
						
						clients.get(senderId).timeSinceLastHeartbeat = 0L;
						break;
					case MSG_UPDATE_PLAYER:
						//make sure the client exists
						if(!clients.containsKey(senderId)) {
							logFile.writeString("Client with ID: " + senderId + " tried to update its position before connecting\n", true);
							break;
						}

						//TODO update local copy of Player
						
						//tell all clients of the sender's new position
						synchronized(clients) {
							for(Client c : clients.values()) {
								if(!c.clientId.equals(senderId)) {
									c.send(socket, packet);
								}
							}
						}
						break;
					case MSG_SPAWN_BOLT:
						//make sure the client exists
						if(!clients.containsKey(senderId)) {
							logFile.writeString("Client with ID: " + senderId + " tried to fire a bolt before connecting\n", true);
							break;
						}
						
						//add bolt to list of actors
						UUID boltNetId = new UUID(data.getLong(), data.getLong());
						float boltX = data.getFloat();
						float boltY = data.getFloat();
						float boltRot = data.getFloat();
						float boltSpeed = data.getFloat();
						Bolt newBolt = boltPool.obtain().setShooterId(senderId).setSpeed(boltSpeed).setNetId(boltNetId);
						newBolt.setPosition(boltX, boltY);
						newBolt.setRotation(boltRot);
						synchronized(netActors) {
							netActors.add(newBolt);
						}
						
						//tell all clients that a bolt was spawned
						synchronized(clients) {
							for(Client c : clients.values()) {
								if(!c.clientId.equals(senderId)) {
									c.send(socket, packet);
								}
							}
						}
					}
				} catch(Exception e) {
					logFile.writeString("Failed to receive a packet\n", true);
				}
			}
		}, "io_thread");
		
		//simulates movement of server authoritative entities
		simulationThread = new Thread(() -> {
			DatagramPacket packet = new DatagramPacket(new byte[PACKET_LENGTH], PACKET_LENGTH);
			ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
			
			float time = System.currentTimeMillis() / 1000f;
			while(!Thread.interrupted()) {
				float deltaTime = System.currentTimeMillis() / 1000f - time;
				time = System.currentTimeMillis() / 1000f;
				
				//simulate actors
				synchronized(netActors) {
					Iterator<NetActor> iter = netActors.iterator();
					while(iter.hasNext()) {
						NetActor actor = iter.next();
						if(actor instanceof Bolt) {
							Bolt bolt = (Bolt)actor;
							bolt.moveBy(bolt.getSpeedCompX() * deltaTime, bolt.getSpeedCompY() * deltaTime);
							//remove the bolt if its out of bounds
							if(bolt.getX() < 0 || bolt.getX() > 2000 || bolt.getY() < 0 || bolt.getY() > 1000) {
								Pools.free(bolt);
								iter.remove();
								
								//Send a MSG_REMOVE_BOLT packet to all clients
								buffer.rewind();
								int dataLength = 32; //4 longs
								buffer.putInt(dataLength);
								buffer.put(MSG_REMOVE_BOLT);
								buffer.putLong(bolt.getShooterId().getMostSignificantBits());
								buffer.putLong(bolt.getShooterId().getLeastSignificantBits());
								buffer.putLong(bolt.getNetId().getMostSignificantBits());
								buffer.putLong(bolt.getNetId().getLeastSignificantBits());
								buffer.putLong(0);
								buffer.putLong(0);
								packet.setLength(HEADER_LENGTH + dataLength);
								synchronized(clients) {
									for(Client c : clients.values()) {
										c.send(socket, packet);
									}
								}
							}
						}
					}
				}
				
				//send packets to update the actors for clients
				synchronized(clients) {
					for(Client client : clients.values()) {
						synchronized(netActors) {
							for(NetActor actor : netActors) {
								if(actor instanceof Bolt) {
									Bolt bolt = (Bolt)actor;
									buffer.rewind();
									int dataLength = 28; //2 longs & 3 floats
									buffer.putInt(dataLength);
									buffer.put(MSG_UPDATE);
									buffer.putLong(bolt.getShooterId().getMostSignificantBits());
									buffer.putLong(bolt.getShooterId().getLeastSignificantBits());
									buffer.putLong(bolt.getNetId().getMostSignificantBits());
									buffer.putLong(bolt.getNetId().getLeastSignificantBits());
									buffer.putFloat(bolt.getX());
									buffer.putFloat(bolt.getY());
									buffer.putFloat(bolt.getRotation());
									packet.setLength(HEADER_LENGTH + dataLength);
									client.send(socket, packet);
								}
							}
						}
					}
				}
			}
		}, "simulation_thread");
		
		//keeps track of every clients heart beat to ensure it is still connected
		heartbeatThread = new Thread(() -> {
			final DatagramPacket packet = new DatagramPacket(new byte[PACKET_LENGTH], PACKET_LENGTH);
			final ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
			
			long time = System.currentTimeMillis();
			while(!Thread.interrupted()) {
				long deltaTime = System.currentTimeMillis() - time;
				time = System.currentTimeMillis();
				
				//check each clients heartbeat
				Array<UUID> removedClients = null;
				for(Entry<UUID, Client> timeEntry : clients.entrySet()) {
					timeEntry.getValue().timeSinceLastHeartbeat += deltaTime;
					if(timeEntry.getValue().timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT) { //boot a client if the have no heart beat
						logFile.writeString("Dropping client " + timeEntry.getKey() + " due to lack of heartbeat\n", true);
						
						removedClients = removedClients == null ? new Array<>() : removedClients;
						removedClients.add(timeEntry.getKey());
						
						//send a disconnect to all clients to notify them of the disconnected player
						for(Entry<UUID, Client> clientEntry : clients.entrySet()) {
							//construct disconnect message for all clients
							buffer.rewind();
							buffer.putInt(0);
							buffer.put(MSG_DISCONNECT);
							buffer.putLong(timeEntry.getKey().getMostSignificantBits());
							buffer.putLong(timeEntry.getKey().getLeastSignificantBits());
							packet.setLength(HEADER_LENGTH);
							clientEntry.getValue().send(socket, packet);
						}
					}
				}
				
				//avoid ConcurrentModificationException by removing clients outside of the loop
				if(removedClients != null) {
					for(UUID id : removedClients) {
						clients.remove(id);
					}
				}
			}
		}, "heartbeat_thread");
		
		//start threads
		ioThread.start();
		simulationThread.start();
		heartbeatThread.start();
	}
	
	@Override
	public void dispose() {
		logFile.writeString("Disposing...\n", true);
		
		heartbeatThread.interrupt();
		simulationThread.interrupt();
		ioThread.interrupt();
		
		//wait for each thread to finish
		try {
			heartbeatThread.join();
			simulationThread.join();
			ioThread.join();
		} catch(InterruptedException e) {}
		
		logFile.writeString("Server disposed\n", true);
	}
}
