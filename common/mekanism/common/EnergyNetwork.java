package mekanism.common;

import ic2.api.energy.tile.IEnergySink;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import mekanism.api.energy.IStrictEnergyAcceptor;
import mekanism.api.transmitters.DynamicNetwork;
import mekanism.api.transmitters.ITransmitter;
import mekanism.api.transmitters.TransmissionType;
import mekanism.common.util.CableUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import universalelectricity.core.block.IElectrical;
import universalelectricity.core.electricity.ElectricityPack;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.power.PowerHandler.PowerReceiver;
import buildcraft.api.power.PowerHandler.Type;
import cofh.api.energy.IEnergyHandler;
import cpw.mods.fml.common.FMLCommonHandler;

public class EnergyNetwork extends DynamicNetwork<TileEntity, EnergyNetwork>
{
	public static final int CABLE_ENERGY = 10000;
	
	private double lastPowerScale = 0;
	private double joulesTransmitted = 0;
	private double joulesLastTick = 0;
	
	public double clientEnergyScale = 0;
	
	public double electricityStored;
	
	public EnergyNetwork(ITransmitter<EnergyNetwork>... varCables)
	{
		transmitters.addAll(Arrays.asList(varCables));
		register();
	}
	
	public EnergyNetwork(Collection<ITransmitter<EnergyNetwork>> collection)
	{
		transmitters.addAll(collection);
		register();
	}
	
	public EnergyNetwork(Set<EnergyNetwork> networks)
	{
		for(EnergyNetwork net : networks)
		{
			if(net != null)
			{
				if(net.joulesLastTick > joulesLastTick || net.clientEnergyScale > clientEnergyScale)
				{
					clientEnergyScale = net.clientEnergyScale;
					joulesLastTick = net.joulesLastTick;
					joulesTransmitted = net.joulesTransmitted;
					lastPowerScale = net.lastPowerScale;
				}
				
				addAllTransmitters(net.transmitters);
				net.deregister();
			}
		}
		
		refresh();
		register();
	}
	
	public double getCapacity()
	{
		return CABLE_ENERGY*transmitters.size();
	}
	
	public synchronized double getEnergyNeeded()
	{
		if(FMLCommonHandler.instance().getEffectiveSide().isClient())
		{
			return 0;
		}
		
		return getCapacity()-electricityStored;
	}
	
	public synchronized double tickEmit(double energyToSend)
	{
		if(FMLCommonHandler.instance().getEffectiveSide().isClient())
		{
			return energyToSend;
		}
		
		double prevEnergy = energyToSend;
		double sent;

		energyToSend = doEmit(energyToSend);
		sent = prevEnergy-energyToSend;
		
		boolean tryAgain = energyToSend > 0 && sent > 0;
		
		while(tryAgain)
		{
			tryAgain = false;
			
			prevEnergy = energyToSend;
			sent = 0;
			
			energyToSend -= (energyToSend - doEmit(energyToSend));
			sent = prevEnergy-energyToSend;
			
			if(energyToSend > 0 && sent > 0)
			{
				tryAgain = true;
			}
		}
		
		return energyToSend;
	}
	
	public synchronized double emit(double energyToSend)
	{
		double toUse = Math.min(getEnergyNeeded(), energyToSend);
		electricityStored += toUse;
		return energyToSend-toUse;
	}
	
	/**
	 * @return rejects
	 */
	public synchronized double doEmit(double energyToSend)
	{
		double energyAvailable = energyToSend;		
		double sent;
		
		List availableAcceptors = Arrays.asList(getAcceptors().toArray());

		Collections.shuffle(availableAcceptors);

		if(!availableAcceptors.isEmpty())
		{
			int divider = availableAcceptors.size();
			double remaining = energyToSend % divider;
			double sending = (energyToSend-remaining)/divider;

			for(Object obj : availableAcceptors)
			{
				if(obj instanceof TileEntity)
				{
					TileEntity acceptor = (TileEntity)obj;
					double currentSending = sending+remaining;
					ForgeDirection side = acceptorDirections.get(acceptor);
					
					if(side == null)
					{
						continue;
					}
					
					remaining = 0;
					
					if(acceptor instanceof IStrictEnergyAcceptor)
					{
						energyToSend -= (currentSending - ((IStrictEnergyAcceptor)acceptor).transferEnergyToAcceptor(side.getOpposite(), currentSending));
					}
					else if(acceptor instanceof IEnergyHandler)
					{
						IEnergyHandler handler = (IEnergyHandler)acceptor;
						int used = handler.receiveEnergy(side.getOpposite(), (int)Math.round(currentSending*Mekanism.TO_TE), false);
						energyToSend -= used*Mekanism.FROM_TE;
					}
					else if(acceptor instanceof IEnergySink)
					{
						double toSend = Math.min(currentSending, ((IEnergySink)acceptor).getMaxSafeInput()*Mekanism.FROM_IC2);
						toSend = Math.min(toSend, ((IEnergySink)acceptor).demandedEnergyUnits()*Mekanism.FROM_IC2);
						energyToSend -= (toSend - (((IEnergySink)acceptor).injectEnergyUnits(side.getOpposite(), toSend*Mekanism.TO_IC2)*Mekanism.FROM_IC2));
					}
					else if(acceptor instanceof IPowerReceptor && MekanismUtils.useBuildcraft())
					{
						PowerReceiver receiver = ((IPowerReceptor)acceptor).getPowerReceiver(side.getOpposite());
						
						if(receiver != null)
						{
			            	float toSend = receiver.receiveEnergy(Type.PIPE, (float)(Math.min(receiver.powerRequest(), currentSending*Mekanism.TO_BC)), side.getOpposite());
			            	energyToSend -= toSend*Mekanism.FROM_BC;
						}
					}
					else if(acceptor instanceof IElectrical)
					{
						double toSend = Math.min(currentSending, ((IElectrical)acceptor).getRequest(side.getOpposite())*Mekanism.FROM_UE);
						ElectricityPack pack = ElectricityPack.getFromWatts((float)(toSend*Mekanism.TO_UE), ((IElectrical)acceptor).getVoltage());
						energyToSend -= ((IElectrical)acceptor).receiveElectricity(side.getOpposite(), pack, true)*Mekanism.FROM_UE;
					}
				}
			}
			
			sent = energyAvailable - energyToSend;
			joulesTransmitted += sent;
		}
		
		return energyToSend;
	}
	
	@Override
	public synchronized Set<TileEntity> getAcceptors(Object... data)
	{
		Set<TileEntity> toReturn = new HashSet<TileEntity>();
		
		if(FMLCommonHandler.instance().getEffectiveSide().isClient())
		{
			return toReturn;
		}
		
		Set<TileEntity> copy = (Set<TileEntity>)possibleAcceptors.clone();
		
		for(TileEntity acceptor : copy)
		{
			ForgeDirection side = acceptorDirections.get(acceptor);
			
			if(side == null)
			{
				continue;
			}
			
			if(acceptor instanceof IStrictEnergyAcceptor)
			{
				IStrictEnergyAcceptor handler = (IStrictEnergyAcceptor)acceptor;
				
				if(handler.canReceiveEnergy(side.getOpposite()))
				{
					if(handler.getMaxEnergy() - handler.getEnergy() > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IEnergyHandler)
			{
				IEnergyHandler handler = (IEnergyHandler)acceptor;
				
				if(handler.canInterface(side.getOpposite()))
				{
					if(handler.getMaxEnergyStored(side.getOpposite()) - handler.getEnergyStored(side.getOpposite()) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IEnergySink)
			{
				IEnergySink handler = (IEnergySink)acceptor;
				
				if(handler.acceptsEnergyFrom(null, side.getOpposite()))
				{
					if(Math.min((handler.demandedEnergyUnits()*Mekanism.FROM_IC2), (handler.getMaxSafeInput()*Mekanism.FROM_IC2)) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IElectrical)
			{
				IElectrical handler = (IElectrical)acceptor;
				
				if(handler.canConnect(side.getOpposite()))
				{
					if(handler.getRequest(side.getOpposite()) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IPowerReceptor && MekanismUtils.useBuildcraft())
			{
				IPowerReceptor handler = (IPowerReceptor)acceptor;
				
				if(handler.getPowerReceiver(side.getOpposite()) != null)
				{
					if((handler.getPowerReceiver(side.getOpposite()).powerRequest()*Mekanism.FROM_BC) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
		}
		
		return toReturn;
	}

	@Override
	public synchronized void refresh()
	{
		Set<ITransmitter<EnergyNetwork>> iterCables = (Set<ITransmitter<EnergyNetwork>>)transmitters.clone();
		Iterator<ITransmitter<EnergyNetwork>> it = iterCables.iterator();
		
		possibleAcceptors.clear();
		acceptorDirections.clear();

		while(it.hasNext())
		{
			ITransmitter<EnergyNetwork> conductor = (ITransmitter<EnergyNetwork>)it.next();

			if(conductor == null || ((TileEntity)conductor).isInvalid())
			{
				it.remove();
				transmitters.remove(conductor);
			}
			else {
				conductor.setTransmitterNetwork(this);
			}
		}
		
		for(ITransmitter<EnergyNetwork> cable : iterCables)
		{
			TileEntity[] acceptors = CableUtils.getConnectedEnergyAcceptors((TileEntity)cable);
		
			for(TileEntity acceptor : acceptors)
			{
				if(acceptor != null && !(acceptor instanceof ITransmitter))
				{
					possibleAcceptors.add(acceptor);
					acceptorDirections.put(acceptor, ForgeDirection.getOrientation(Arrays.asList(acceptors).indexOf(acceptor)));
				}
			}
		}
		
		needsUpdate = true;
	}

	@Override
	public synchronized void merge(EnergyNetwork network)
	{
		if(network != null && network != this)
		{
			Set<EnergyNetwork> networks = new HashSet<EnergyNetwork>();
			networks.add(this);
			networks.add(network);
			EnergyNetwork newNetwork = create(networks);
			newNetwork.refresh();
		}
	}
	
	public static class EnergyTransferEvent extends Event
	{
		public final EnergyNetwork energyNetwork;
		
		public final double power;
		
		public EnergyTransferEvent(EnergyNetwork network, double currentPower)
		{
			energyNetwork = network;
			power = currentPower;
		}
	}

	@Override
	public String toString()
	{
		return "[EnergyNetwork] " + transmitters.size() + " transmitters, " + possibleAcceptors.size() + " acceptors.";
	}

	@Override
	public void tick()
	{	
		super.tick();
		
		clearJoulesTransmitted();
		
		double currentPowerScale = getPowerScale();
		
		if(FMLCommonHandler.instance().getEffectiveSide().isServer())
		{
			if(Math.abs(currentPowerScale-lastPowerScale) > 0.01 || (currentPowerScale != lastPowerScale && (currentPowerScale == 0 || currentPowerScale == 1)))
			{
				needsUpdate = true;
			}
			
			lastPowerScale = currentPowerScale;

			if(needsUpdate)
			{
				MinecraftForge.EVENT_BUS.post(new EnergyTransferEvent(this, currentPowerScale));
				needsUpdate = false;
			}
			
			if(electricityStored > 0)
			{
				electricityStored -= (electricityStored - tickEmit(electricityStored));
			}
		}
	}
	
	public double getPowerScale()
	{
		return Math.max(joulesLastTick == 0 ? 0 : Math.min(Math.ceil(Math.log10(getPower())*2)/10, 1), electricityStored/getCapacity());
	}
	
	public void clearJoulesTransmitted()
	{
		joulesLastTick = joulesTransmitted;
		joulesTransmitted = 0;
	}
	
	public double getPower()
	{
		return joulesLastTick * 20;
	}
	
	@Override
	protected EnergyNetwork create(ITransmitter<EnergyNetwork>... varTransmitters) 
	{
		EnergyNetwork network = new EnergyNetwork(varTransmitters);
		network.clientEnergyScale = clientEnergyScale;
		network.joulesLastTick = joulesLastTick;
		network.joulesTransmitted = joulesTransmitted;
		network.lastPowerScale = lastPowerScale;
		return network;
	}

	@Override
	protected EnergyNetwork create(Collection<ITransmitter<EnergyNetwork>> collection) 
	{
		EnergyNetwork network = new EnergyNetwork(collection);
		network.clientEnergyScale = clientEnergyScale;
		network.joulesLastTick = joulesLastTick;
		network.joulesTransmitted = joulesTransmitted;
		network.lastPowerScale = lastPowerScale;
		return network;
	}

	@Override
	protected EnergyNetwork create(Set<EnergyNetwork> networks) 
	{
		EnergyNetwork network = new EnergyNetwork(networks);
		
		if(joulesLastTick > network.joulesLastTick || clientEnergyScale > network.clientEnergyScale)
		{
			network.clientEnergyScale = clientEnergyScale;
			network.joulesLastTick = joulesLastTick;
			network.joulesTransmitted = joulesTransmitted;
			network.lastPowerScale = lastPowerScale;
		}
		
		return network;
	}
	
	@Override
	public TransmissionType getTransmissionType()
	{
		return TransmissionType.ENERGY;
	}
	
	@Override
	public String getNeeded()
	{
		return MekanismUtils.getEnergyDisplay(getEnergyNeeded());
	}

	@Override
	public String getFlow()
	{
		return MekanismUtils.getEnergyDisplay(electricityStored);
	}
}
